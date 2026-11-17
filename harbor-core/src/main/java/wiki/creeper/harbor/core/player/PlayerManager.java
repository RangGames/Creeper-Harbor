package wiki.creeper.harbor.core.player;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.session.ClaimResult;
import wiki.creeper.harbor.common.session.SessionAudit;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.common.session.SessionProtocol;
import wiki.creeper.harbor.common.session.SessionProtocol.HandoffReply;
import wiki.creeper.harbor.common.session.SessionProtocol.HandoffRequest;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.PlayerService;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 플레이어 데이터 수명 주기.
 *
 * <ul>
 *   <li>접속 전 (AsyncPlayerPreLoginEvent): 세션 잠금 확인 → players·player_season 로드 → 모듈 컴포넌트를 병렬 로드 → 실패하면 접속
 *       거부 (반쪽 데이터로 입장 금지)
 *   <li>접속 중: 메인 스레드에서 메모리 캐시만 읽고 쓴다. 바뀐 항목은 dirty 표시
 *   <li>자동 저장: 5분마다 바뀐 항목을 지연 쓰기 큐로
 *   <li>퇴장·서버 이동: 그 플레이어 키만 즉시 저장 → 잠금 해제 또는 handoff 전환
 * </ul>
 *
 * 로비 서버는 읽기 전용이다 (잠금을 잡지 않고 저장하지 않는다).
 */
public final class PlayerManager implements PlayerService, Listener {

    private static final long AUTOSAVE_TICKS = 5 * 60 * 20L;
    private static final long LOCK_REFRESH_TICKS = 10 * 20L;
    private static final long PENDING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long STUCK_HANDOFF_TICKS = 10 * 20L;
    private static final long LOAD_TIMEOUT_SECONDS = 5;

    private record Pending(PlayerState state, long createdNanos) {}

    private final Map<UUID, PlayerState> online = new HashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, PlayerComponent<?>> components = new ConcurrentHashMap<>();
    private final InfraConfig infra;
    private final Database db;
    private final WriteBehindQueue queue;
    private final SessionLocks locks;
    private final MessageBus bus;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;
    private final boolean readOnly;
    private volatile boolean acceptingLogins = true;

    public PlayerManager(
            InfraConfig infra,
            Database db,
            WriteBehindQueue queue,
            SessionLocks locks,
            MessageBus bus,
            MessageService messages,
            Tasks tasks,
            Logger log) {
        this.infra = infra;
        this.db = db;
        this.queue = queue;
        this.locks = locks;
        this.bus = bus;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
        this.readOnly = infra.role() == ServerRole.LOBBY;
    }

    public void start() {
        var unused1 = tasks.timer(AUTOSAVE_TICKS, AUTOSAVE_TICKS, this::autosave);
        var unused2 = tasks.timer(LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS, this::refreshLocks);
        var unused3 = tasks.timer(LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS, this::cleanupPending);
        bus.subscribe(SessionProtocol.HANDOFF_REQUEST, envelope -> {
            HandoffRequest request = envelope.payload();
            if (request.from().equals(infra.serverId())) {
                tasks.main().execute(() -> handleHandoff(request));
            }
        });
    }

    // ------------------------------------------------------------------ PlayerService

    @Override
    public Optional<HarborPlayer> get(UUID player) {
        return Optional.ofNullable(online.get(player));
    }

    @Override
    public HarborPlayer get(Player player) {
        PlayerState state = online.get(player.getUniqueId());
        if (state == null) {
            throw new IllegalStateException("플레이어 데이터가 없습니다: " + player.getName());
        }
        return state;
    }

    @Override
    public Collection<? extends HarborPlayer> online() {
        return Collections.unmodifiableCollection(online.values());
    }

    @Override
    public void register(PlayerComponent<?> component) {
        if (components.putIfAbsent(component.id(), component) != null) {
            throw new IllegalArgumentException("플레이어 컴포넌트 '" + component.id() + "'가 이미 등록되어 있습니다");
        }
        if (!online.isEmpty() || !pending.isEmpty()) {
            log.warn("접속자가 있는 상태에서 컴포넌트 '{}'가 등록되었습니다. 지금 접속한 플레이어에게는 없습니다", component.id());
        }
    }

    /** 재화 서비스가 커밋한 뒤 캐시 잔액을 바꾼다 (메인 스레드). */
    public void updateCachedBalance(UUID player, Currency currency, long balance) {
        PlayerState state = online.get(player);
        if (state != null) {
            state.setCachedBalance(currency, balance);
        }
    }

    // ------------------------------------------------------------------ 접속·퇴장

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        if (!acceptingLogins) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("server.closing"));
            return;
        }
        if (!readOnly) {
            ClaimResult claim = locks.awaitClaim(uuid, infra.serverId(), SessionLocks.HANDOFF_WAIT);
            if (!claim.isClaimed()) {
                String key = claim == ClaimResult.BUSY ? "session.busy" : "session.unavailable";
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get(key));
                return;
            }
        }
        try {
            PlayerState state = load(uuid, event.getName());
            pending.put(uuid, new Pending(state, System.nanoTime()));
        } catch (SQLException | ExecutionException | TimeoutException | RuntimeException e) {
            log.error("플레이어 데이터 로드 실패: {} ({})", event.getName(), uuid, e);
            if (!readOnly) {
                locks.release(uuid, infra.serverId());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("player.load_failed"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("player.load_failed"));
            return;
        }
        if (!readOnly) {
            Instant until = Instant.now().plus(SessionLocks.TTL);
            var unused = db.asyncVoid(c -> SessionAudit.recordOwner(c, uuid, infra.serverId(), until));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Pending loaded = pending.remove(player.getUniqueId());
        if (loaded == null) {
            player.kick(messages.get("player.load_failed"));
            return;
        }
        loaded.state().markJoined();
        online.put(player.getUniqueId(), loaded.state());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Sequence.cancelAll(uuid);
        PlayerState state = online.remove(uuid);
        if (state == null || state.handingOff() || readOnly) {
            // handoff 중이면 이미 저장하고 잠금을 넘겼다
            return;
        }
        enqueueSave(state, false);
        var unused =
                tasks.async(
                        () -> {
                            try {
                                queue.flushNow(prefix(uuid));
                                locks.release(uuid, infra.serverId());
                            } catch (SQLException e) {
                                // 큐에 남아 있다가 다시 쓴다. 잠금은 해제하지 않고 30초 뒤 만료되게 둔다
                                log.error("퇴장 저장 실패, 지연 쓰기로 다시 시도합니다: {}", uuid, e);
                            }
                        });
    }

    // ------------------------------------------------------------------ 서버 이동 (출발 서버)

    private void handleHandoff(HandoffRequest request) {
        PlayerState state = online.get(request.player());
        if (state == null || state.handingOff() || readOnly) {
            bus.publish(
                    SessionProtocol.HANDOFF_REPLY,
                    new HandoffReply(request.requestId(), request.player(), false, "not_online"));
            return;
        }
        state.setHandingOff(true);
        enqueueSave(state, false);
        UUID uuid = request.player();
        var unused =
                tasks.async(
                        () -> {
                            boolean ok = false;
                            String reason;
                            try {
                                queue.flushNow(prefix(uuid));
                                ok = locks.beginHandoff(uuid, infra.serverId(), request.to());
                                reason = ok ? "ok" : "lock_lost";
                            } catch (SQLException e) {
                                log.error("서버 이동 저장 실패: {}", uuid, e);
                                reason = "save_failed";
                            }
                            bus.publish(
                                    SessionProtocol.HANDOFF_REPLY,
                                    new HandoffReply(request.requestId(), uuid, ok, reason));
                            if (ok) {
                                var unused2 = tasks.later(STUCK_HANDOFF_TICKS, () -> checkStuckHandoff(state));
                            } else {
                                tasks.main().execute(() -> state.setHandingOff(false));
                            }
                        });
    }

    /** handoff 후에도 플레이어가 남아 있으면 (프록시가 이동을 취소함) 소유권을 되찾는다. */
    private void checkStuckHandoff(PlayerState state) {
        if (online.get(state.uuid()) != state) {
            return;
        }
        var unused =
                tasks.supplyAsync(() -> locks.cancelHandoff(state.uuid(), infra.serverId()))
                        .thenAcceptAsync(
                                reclaimed -> {
                                    if (reclaimed) {
                                        state.setHandingOff(false);
                                        log.warn("서버 이동이 취소되어 세션을 되찾았습니다: {}", state.name());
                                        return;
                                    }
                                    Player player = Bukkit.getPlayer(state.uuid());
                                    if (player != null) {
                                        // 다른 서버가 이미 데이터를 가져갔다. 여기 남으면 복제 위험
                                        player.kick(messages.get("session.moved"));
                                    }
                                },
                                tasks.main());
    }

    // ------------------------------------------------------------------ 저장

    private void autosave() {
        if (readOnly) {
            return;
        }
        for (PlayerState state : online.values()) {
            if (!state.handingOff()) {
                enqueueSave(state, false);
            }
        }
    }

    /** 메인 스레드: 복사본을 만들어 지연 쓰기 큐에 넣는다. */
    private void enqueueSave(PlayerState state, boolean allComponents) {
        UUID uuid = state.uuid();
        SeasonId season = state.season();
        PlayerState.BaseSnapshot base = state.snapshotBase();
        queue.enqueue(prefix(uuid) + "base", c -> PlayerRepository.saveBase(c, base));
        state.clearBaseDirty();
        for (String id : state.drainDirtyComponents(allComponents)) {
            PlayerComponent<?> component = components.get(id);
            Object value = state.component(id);
            if (component == null || value == null) {
                continue;
            }
            queue.enqueue(prefix(uuid) + "c:" + id, c -> save(component, c, uuid, season, value));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void save(PlayerComponent<T> component, Connection c, UUID uuid, SeasonId season, Object value)
            throws SQLException {
        component.saver().save(c, uuid, season, (T) value);
    }

    /** 서버 종료: 새 접속 차단 → 접속자 저장 → 큐 비우기는 호출한 쪽이 한다. */
    public void shutdown() {
        acceptingLogins = false;
        if (readOnly) {
            return;
        }
        List<UUID> players = new ArrayList<>(online.keySet());
        for (PlayerState state : online.values()) {
            if (!state.handingOff()) {
                enqueueSave(state, false);
            }
        }
        for (UUID uuid : players) {
            try {
                queue.flushNow(prefix(uuid));
                locks.release(uuid, infra.serverId());
            } catch (SQLException e) {
                log.error("종료 저장 실패: {}", uuid, e);
            }
        }
        online.clear();
    }

    private void refreshLocks() {
        if (readOnly || online.isEmpty()) {
            return;
        }
        List<UUID> owned = new ArrayList<>();
        for (PlayerState state : online.values()) {
            if (!state.handingOff()) {
                owned.add(state.uuid());
            }
        }
        var unused = tasks.async(() -> locks.refresh(owned, infra.serverId()));
    }

    /** 접속 전 로드는 했지만 접속하지 않은 플레이어 정리. */
    private void cleanupPending() {
        long now = System.nanoTime();
        pending.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().createdNanos() > PENDING_TIMEOUT_NANOS;
            if (expired && !readOnly) {
                UUID uuid = entry.getKey();
                var unused = tasks.async(() -> locks.release(uuid, infra.serverId()));
            }
            return expired;
        });
    }

    // ------------------------------------------------------------------ 로드

    private PlayerState load(UUID uuid, String name)
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        SeasonId season = infra.season();
        PlayerRepository.Row row = db.tx(c -> PlayerRepository.loadOrCreate(c, uuid, name, season));
        Map<String, Future<Object>> futures = new LinkedHashMap<>();
        for (PlayerComponent<?> component : components.values()) {
            futures.put(
                    component.id(),
                    db.executor().submit(() -> (Object) db.tx(c -> component.loader().load(c, uuid, season))));
        }
        Map<String, Object> loaded = new HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOAD_TIMEOUT_SECONDS);
        try {
            for (Map.Entry<String, Future<Object>> entry : futures.entrySet()) {
                long left = Math.max(0, deadline - System.nanoTime());
                loaded.put(entry.getKey(), entry.getValue().get(left, TimeUnit.NANOSECONDS));
            }
        } finally {
            futures.values().forEach(f -> f.cancel(true));
        }
        return new PlayerState(row, loaded);
    }

    private static String prefix(UUID uuid) {
        return "player:" + uuid + ":";
    }
}
