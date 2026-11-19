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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.SaveAll;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferAbort;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferPrepare;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferReady;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.session.ClaimResult;
import wiki.creeper.harbor.common.session.SessionAudit;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.core.api.HandoffBlocker;
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
 *   <li>접속 전 (AsyncPlayerPreLoginEvent): 세션 잠금 확인 → players·player_season 로드 → 모듈 컴포넌트를 병렬 로드 →
 *       실패하면 접속 거부 (반쪽 데이터로 입장 금지)
 *   <li>접속 중: 메인 스레드에서 메모리 캐시만 읽고 쓴다. 바뀐 항목은 dirty 표시
 *   <li>자동 저장: 5분마다 바뀐 항목을 지연 쓰기 큐로
 *   <li>퇴장: 그 플레이어 키만 즉시 저장 → 잠금 해제
 *   <li>서버 이동 (프록시의 TransferPrepare): 즉시 저장 → 잠금을 handoff로 → TransferReady. TransferAbort면 되돌린다
 * </ul>
 *
 * 로비도 본 서버와 똑같이 잠금을 잡고 저장한다 (인벤토리 동기화로 로비에서도 플레이어 데이터가 바뀌기 때문, 추가 제안. 예전에는 로비가 읽기 전용이었다). 저장 직전에
 * {@link SaveHook}이 메인 스레드에서 Bukkit 상태(인벤토리 등)를 컴포넌트에 담는다.
 */
public final class PlayerManager implements PlayerService, Listener {

    private static final long AUTOSAVE_TICKS = 5 * 60 * 20L;
    private static final long LOCK_REFRESH_TICKS = 10 * 20L;
    private static final long PENDING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** 프록시의 TransferAbort가 오지 않을 때를 대비한 안전 확인 (프록시 쪽 제한 5초보다 길게). */
    private static final long STUCK_HANDOFF_TICKS = 15 * 20L;

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
    private volatile boolean acceptingLogins = true;

    /** 저장 이유 (SaveHook·기록용). */
    public enum SaveReason {
        AUTOSAVE,
        QUIT,
        HANDOFF,
        SHUTDOWN,
        NOW
    }

    /** 저장 직전 훅 (메인 스레드, 플레이어가 이 서버에 있을 때만). 컴포넌트에 값을 넣으면 같은 저장에 실린다. */
    @FunctionalInterface
    public interface SaveHook {
        void beforeSave(Player player, HarborPlayer data, SaveReason reason);
    }

    private final List<SaveHook> saveHooks = new CopyOnWriteArrayList<>();

    public void addSaveHook(SaveHook hook) {
        saveHooks.add(hook);
    }

    private final List<HandoffBlocker> handoffBlockers = new CopyOnWriteArrayList<>();

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
    }

    public void start() {
        var _ = tasks.timer(AUTOSAVE_TICKS, AUTOSAVE_TICKS, this::autosave);
        var _ = tasks.timer(LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS, this::refreshLocks);
        var _ = tasks.timer(LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS, this::cleanupPending);
        String me = infra.serverId();
        bus.subscribe(
                ProxyMessages.toServer(me, TransferPrepare.class),
                envelope -> tasks.main().execute(() -> prepareTransfer(envelope.payload())));
        bus.subscribe(
                ProxyMessages.toServer(me, TransferAbort.class),
                envelope ->
                        tasks.main()
                                .execute(
                                        () ->
                                                abortTransfer(
                                                        envelope.payload().player(),
                                                        envelope.payload().requestId())));
        // 점검 직전 프록시의 저장 신호: 접속자 전원을 지금 저장한다
        bus.subscribe(
                ProxyMessages.toServer(me, SaveAll.class),
                envelope -> tasks.main().execute(this::saveAllNow));
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
    public CompletableFuture<Optional<PlayerRef>> find(String name) {
        Player local = Bukkit.getPlayerExact(name);
        if (local != null) {
            return CompletableFuture.completedFuture(
                    Optional.of(new PlayerRef(local.getUniqueId(), local.getName())));
        }
        return db.async(
                        c -> {
                            try (var ps =
                                    c.prepareStatement(
                                            "SELECT uuid, name FROM players WHERE name = ?"
                                                    + " ORDER BY last_join DESC LIMIT 1")) {
                                ps.setString(1, name);
                                try (var rs = ps.executeQuery()) {
                                    return rs.next()
                                            ? Optional.of(
                                                    new PlayerRef(
                                                            UUID.fromString(rs.getString(1)),
                                                            rs.getString(2)))
                                            : Optional.<PlayerRef>empty();
                                }
                            }
                        })
                .thenApplyAsync(r -> r, tasks.main());
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

    /** 새 접속을 받는 중인지 (하트비트에 실어 보낸다). */
    public boolean acceptingLogins() {
        return acceptingLogins;
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
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("server.closing"));
            return;
        }
        ClaimResult claim = locks.awaitClaim(uuid, infra.serverId(), SessionLocks.HANDOFF_WAIT);
        if (!claim.isClaimed()) {
            String key = claim == ClaimResult.BUSY ? "session.busy" : "session.unavailable";
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get(key));
            return;
        }
        try {
            PlayerState state = load(uuid, event.getName());
            pending.put(uuid, new Pending(state, System.nanoTime()));
        } catch (SQLException | ExecutionException | TimeoutException | RuntimeException e) {
            log.error("플레이어 데이터 로드 실패: {} ({})", event.getName(), uuid, e);
            locks.release(uuid, infra.serverId());
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("player.load_failed"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("player.load_failed"));
            return;
        }
        Instant until = Instant.now().plus(SessionLocks.TTL);
        var _ = db.asyncVoid(c -> SessionAudit.recordOwner(c, uuid, infra.serverId(), until));
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
        PlayerState state = online.get(uuid);
        if (state == null) {
            return;
        }
        String handoff = state.handoffRequest();
        if (handoff == null) {
            // 저장 훅이 Bukkit 상태를 뜰 수 있게 목록에서 빼기 전에 큐에 넣는다
            enqueueSave(state, false, SaveReason.QUIT);
        }
        online.remove(uuid);
        if (handoff != null) {
            // 이동 중: 이미 저장했다. 도착 서버가 아직 가져가지 않았으면 (이동 중 접속 끊김) 그 요청의 잠금만 지운다
            var _ = tasks.async(() -> locks.releaseHandoff(uuid, handoff));
            return;
        }
        var _ =
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

    /** 이동 전 훅 등록 (docs 20 0.2 HandoffBlocker). 돌려준 Runnable을 부르면 해제한다. */
    public Runnable addHandoffBlocker(HandoffBlocker blocker) {
        handoffBlockers.add(blocker);
        return () -> handoffBlockers.remove(blocker);
    }

    private void prepareTransfer(TransferPrepare request) {
        UUID uuid = request.player();
        PlayerState state = online.get(uuid);
        if (state == null || state.handingOff()) {
            String reason = state == null ? "not_online" : "already_moving";
            bus.publish(
                    ProxyMessages.toProxy(TransferReady.class),
                    new TransferReady(request.requestId(), uuid, false, reason));
            return;
        }
        // 모듈 훅: 거부(컷씬 중 등)면 바로 취소, 지연(거래·기부 진행 중)이면 최대 4초 기다린다
        Player player = Bukkit.getPlayer(uuid);
        List<CompletableFuture<?>> delays = new ArrayList<>();
        if (player != null) {
            for (HandoffBlocker blocker : handoffBlockers) {
                HandoffBlocker.Verdict verdict;
                try {
                    verdict = blocker.check(player, request.destination());
                } catch (RuntimeException e) {
                    log.error("이동 전 훅 오류 (무시하고 진행): {}", uuid, e);
                    continue;
                }
                switch (verdict) {
                    case HandoffBlocker.Deny(String reason) -> {
                        messages.send(player, "core.handoff.blocked." + reason);
                        bus.publish(
                                ProxyMessages.toProxy(TransferReady.class),
                                new TransferReady(
                                        request.requestId(), uuid, false, "blocked:" + reason));
                        return;
                    }
                    case HandoffBlocker.Delay(CompletableFuture<?> until) -> delays.add(until);
                    case HandoffBlocker.Allow() -> {}
                }
            }
        }
        state.setHandoffRequest(request.requestId());
        if (delays.isEmpty()) {
            beginTransfer(request, state);
            return;
        }
        var _ =
                CompletableFuture.allOf(delays.toArray(CompletableFuture[]::new))
                        .orTimeout(HandoffBlocker.MAX_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        .handle((ok, error) -> null)
                        .thenRunAsync(
                                () -> {
                                    if (request.requestId().equals(state.handoffRequest())
                                            && online.get(uuid) == state) {
                                        beginTransfer(request, state);
                                    }
                                },
                                tasks.main());
    }

    private void beginTransfer(TransferPrepare request, PlayerState state) {
        UUID uuid = request.player();
        Sequence.cancelAll(uuid);
        enqueueSave(state, false, SaveReason.HANDOFF);
        var _ =
                tasks.async(
                        () -> {
                            boolean ok = false;
                            String reason;
                            try {
                                queue.flushNow(prefix(uuid));
                                ok =
                                        locks.beginHandoff(
                                                uuid,
                                                infra.serverId(),
                                                request.destination(),
                                                request.requestId());
                                reason = ok ? "ok" : "lock_lost";
                            } catch (SQLException e) {
                                log.error("서버 이동 저장 실패: {}", uuid, e);
                                reason = "save_failed";
                            }
                            bus.publish(
                                    ProxyMessages.toProxy(TransferReady.class),
                                    new TransferReady(request.requestId(), uuid, ok, reason));
                            if (ok) {
                                var _ =
                                        tasks.later(
                                                STUCK_HANDOFF_TICKS,
                                                () -> checkStuckHandoff(uuid, request.requestId()));
                            } else {
                                tasks.main()
                                        .execute(() -> clearHandoff(state, request.requestId()));
                            }
                        });
    }

    /** 프록시가 이동을 취소했다 (TransferAbort): 잠금을 되돌리고 계속 플레이한다. */
    private void abortTransfer(UUID uuid, String requestId) {
        PlayerState state = online.get(uuid);
        if (state == null || !requestId.equals(state.handoffRequest())) {
            return;
        }
        var _ =
                tasks.supplyAsync(() -> locks.abortHandoff(uuid, infra.serverId(), requestId))
                        .thenAcceptAsync(
                                restored -> {
                                    if (restored) {
                                        clearHandoff(state, requestId);
                                    } else {
                                        kickMoved(uuid);
                                    }
                                },
                                tasks.main());
    }

    /** TransferAbort가 오지 않았는데 플레이어가 아직 남아 있으면 스스로 되돌린다. */
    private void checkStuckHandoff(UUID uuid, String requestId) {
        PlayerState state = online.get(uuid);
        if (state != null && requestId.equals(state.handoffRequest())) {
            log.warn("서버 이동 응답이 없어 세션을 되돌립니다: {}", state.name());
            abortTransfer(uuid, requestId);
        }
    }

    private static void clearHandoff(PlayerState state, String requestId) {
        if (requestId.equals(state.handoffRequest())) {
            state.setHandoffRequest(null);
        }
    }

    /** 다른 서버가 이미 데이터를 가져갔다. 여기 남으면 복제 위험이 있으므로 내보낸다. */
    private void kickMoved(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.kick(messages.get("session.moved"));
        }
    }

    // ------------------------------------------------------------------ 저장

    private void autosave() {
        for (PlayerState state : online.values()) {
            if (!state.handingOff()) {
                enqueueSave(state, false, SaveReason.AUTOSAVE);
            }
        }
    }

    /** 메인 스레드: 접속자 전원을 큐에 넣고 바로 쓴다 (점검 직전). 잠금은 그대로 둔다. */
    @Override
    public CompletableFuture<Void> saveNow(UUID uuid) {
        PlayerState state = online.get(uuid);
        if (state == null) {
            return CompletableFuture.completedFuture(null);
        }
        enqueueSave(state, false, SaveReason.NOW);
        return tasks.supplyAsync(
                () -> {
                    try {
                        queue.flushNow(prefix(uuid));
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                    return null;
                });
    }

    private void saveAllNow() {
        autosave();
        var _ = tasks.async(queue::flush);
    }

    /** 메인 스레드: 저장 훅을 돌린 뒤 복사본을 만들어 지연 쓰기 큐에 넣는다. */
    private void enqueueSave(PlayerState state, boolean allComponents, SaveReason reason) {
        UUID uuid = state.uuid();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            for (SaveHook hook : saveHooks) {
                try {
                    hook.beforeSave(player, state, reason);
                } catch (RuntimeException e) {
                    log.error("저장 훅 오류 (무시하고 저장): {} {}", uuid, reason, e);
                }
            }
        }
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
    private static <T> void save(
            PlayerComponent<T> component, Connection c, UUID uuid, SeasonId season, Object value)
            throws SQLException {
        component.saver().save(c, uuid, season, (T) value);
    }

    /** 서버 종료: 새 접속 차단 → 접속자 저장 → 큐 비우기는 호출한 쪽이 한다. */
    public void shutdown() {
        acceptingLogins = false;
        List<UUID> players = new ArrayList<>(online.keySet());
        for (PlayerState state : online.values()) {
            if (!state.handingOff()) {
                enqueueSave(state, false, SaveReason.SHUTDOWN);
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
        if (online.isEmpty()) {
            return;
        }
        List<UUID> owned = new ArrayList<>();
        for (PlayerState state : online.values()) {
            if (!state.handingOff()) {
                owned.add(state.uuid());
            }
        }
        var _ = tasks.async(() -> locks.refresh(owned, infra.serverId()));
    }

    /** 접속 전 로드는 했지만 접속하지 않은 플레이어 정리. */
    private void cleanupPending() {
        long now = System.nanoTime();
        pending.entrySet()
                .removeIf(
                        entry -> {
                            boolean expired =
                                    now - entry.getValue().createdNanos() > PENDING_TIMEOUT_NANOS;
                            if (expired) {
                                UUID uuid = entry.getKey();
                                var _ = tasks.async(() -> locks.release(uuid, infra.serverId()));
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
                    db.executor()
                            .submit(
                                    () ->
                                            (Object)
                                                    db.tx(
                                                            c ->
                                                                    component
                                                                            .loader()
                                                                            .load(
                                                                                    c, uuid,
                                                                                    season))));
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
