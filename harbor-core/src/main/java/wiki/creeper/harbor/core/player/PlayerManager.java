package wiki.creeper.harbor.core.player;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
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
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.session.ClaimResult;
import wiki.creeper.harbor.common.session.SessionAudit;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.ItemCustody;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.PlayerService;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Player data lifecycle.
 *
 * <ul>
 *   <li>Pre-login (AsyncPlayerPreLoginEvent): check the session lock → load players and
 *       player_season → load module components in parallel → deny the login on failure (never join
 *       with half-loaded data)
 *   <li>While online: only the in-memory cache is read and written on the main thread. Changed
 *       entries are marked dirty
 *   <li>Autosave: every 5 minutes, changed entries go to the write-behind queue
 *   <li>Quit: save only that player's keys immediately → release the lock
 *   <li>Server move (the proxy's TransferPrepare): save immediately → turn the lock into a handoff
 *       → TransferReady. TransferAbort rolls it back
 * </ul>
 *
 * The lobby takes the lock and saves exactly like the main server (inventory sync means player data
 * changes in the lobby too, proposed. The lobby used to be read-only). Right before a save, {@link
 * SaveHook} copies Bukkit state (inventory etc.) into components on the main thread.
 *
 * <p>Server move handling lives in {@link HandoffHandler}, rejoin/quit tracking in {@link
 * QuitTracker} and the pre-login load in {@link PlayerLoader}.
 */
public final class PlayerManager implements PlayerService, Listener {

    private static final long AUTOSAVE_TICKS = 5 * 60 * 20L;
    private static final long LOCK_REFRESH_TICKS = 10 * 20L;
    private static final long PENDING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private record Pending(PlayerState state, long createdNanos) {}

    // Modified on the main thread, but frozen() also reads it from other threads
    private final Map<UUID, PlayerState> online = new ConcurrentHashMap<>();
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
    private final QuitTracker quits;
    private final HandoffHandler handoffs;
    private final PlayerLoader loader;
    private final Set<UUID> saveSoonPending = ConcurrentHashMap.newKeySet();
    private volatile LongSupplier saveSoonDelay = () -> 10;

    /** Save reason (for SaveHook and logging). */
    public enum SaveReason {
        AUTOSAVE,
        QUIT,
        HANDOFF,
        SHUTDOWN,
        NOW
    }

    /**
     * Pre-save hook (main thread, only while the player is on this server). Values put into
     * components are included in the same save.
     */
    @FunctionalInterface
    public interface SaveHook {
        void beforeSave(Player player, HarborPlayer data, SaveReason reason);
    }

    private final List<SaveHook> saveHooks = new CopyOnWriteArrayList<>();

    public void addSaveHook(SaveHook hook) {
        saveHooks.add(hook);
    }

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
        this.quits = new QuitTracker(infra, queue, locks, messages, tasks, log);
        this.handoffs =
                new HandoffHandler(
                        infra,
                        queue,
                        locks,
                        bus,
                        messages,
                        tasks,
                        log,
                        online,
                        state -> enqueueSave(state, false, SaveReason.HANDOFF));
        this.loader = new PlayerLoader(infra, db, components);
    }

    public void start() {
        var _ = tasks.timer(AUTOSAVE_TICKS, AUTOSAVE_TICKS, this::autosave);
        var _ = tasks.timer(LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS, this::refreshLocks);
        var _ = tasks.timer(LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS, this::cleanupPending);
        String me = infra.serverId();
        bus.subscribe(
                ProxyMessages.toServer(me, TransferPrepare.class),
                envelope ->
                        tasks.main().execute(() -> handoffs.prepareTransfer(envelope.payload())));
        bus.subscribe(
                ProxyMessages.toServer(me, TransferAbort.class),
                envelope ->
                        tasks.main()
                                .execute(
                                        () ->
                                                handoffs.abortTransfer(
                                                        envelope.payload().player(),
                                                        envelope.payload().requestId())));
        // The proxy's save signal right before maintenance: save every online player now
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

    /** Whether new logins are accepted (sent along with the heartbeat). */
    public boolean acceptingLogins() {
        return acceptingLogins;
    }

    /** Updates the cached balance after the currency service commits (main thread). */
    public void updateCachedBalance(UUID player, Currency currency, long balance) {
        PlayerState state = online.get(player);
        if (state != null) {
            state.setCachedBalance(currency, balance);
        }
    }

    // ------------------------------------------------------------------ Join and quit

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
        // Rejoining the same server: reading before the previous connection's quit save finishes
        // loads unsaved data (everything since the last autosave rolls back and gets duplicated),
        // and the previous connection's lock release removes the new connection's lock (the lock
        // value is the same, "owned:this server")
        if (!quits.awaitPreviousSession(uuid)) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("session.busy"));
            return;
        }
        ClaimResult claim = locks.awaitClaim(uuid, infra.serverId(), SessionLocks.HANDOFF_WAIT);
        if (!claim.isClaimed()) {
            String key = claim == ClaimResult.BUSY ? "session.busy" : "session.unavailable";
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get(key));
            return;
        }
        try {
            PlayerState state = loader.load(uuid, event.getName());
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
        quits.joined(player.getUniqueId());
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
            quits.leftUntracked(uuid);
            return;
        }
        state.markLeft();
        String handoff = state.handoffRequest();
        // Same principle as HuskSync's handlePlayerQuit: if the move snapshot was already taken
        // (locked), don't save again; otherwise save now. If a move was requested but is still
        // waiting on a handoff blocker delay, nothing is saved yet, so treat it as a normal quit
        // (the continuation after the delay stops at the online check)
        if (handoff != null && state.handoffSaved()) {
            online.remove(uuid);
            // If the destination hasn't taken it yet (disconnect mid-move, proxy shutdown), clear
            // only that request's lock. If beginHandoff finishes later than this, beginTransfer
            // sees left() and clears it once more
            quits.trackQuit(uuid, tasks.async(() -> locks.releaseHandoff(uuid, handoff)));
            return;
        }
        if (handoff != null) {
            state.setHandoffRequest(null);
        }
        // Enqueue before removing from the list so save hooks can snapshot Bukkit state
        enqueueSave(state, false, SaveReason.QUIT);
        online.remove(uuid);
        quits.trackQuit(
                uuid,
                tasks.async(
                        () -> {
                            try {
                                queue.flushNow(prefix(uuid));
                                locks.release(uuid, infra.serverId());
                            } catch (SQLException e) {
                                // Still in the queue. Keep extending the lock until it is saved so
                                // other servers can't read unsaved data, and refreshLocks retries
                                // the write (previously the lock expired after 30 seconds, another
                                // server read stale data, and the late retry overwrote that
                                // session)
                                quits.markUnsaved(uuid);
                                log.error("퇴장 저장 실패, 저장될 때까지 잠금을 유지합니다: {}", uuid, e);
                            }
                        }));
    }

    // ------------------------------------------------------------------ Server move (departing
    // server)

    private final List<ItemCustody> custodies = new CopyOnWriteArrayList<>();

    /**
     * Registers a held-item provider ({@link ItemCustody}). Call the returned Runnable to remove
     * it.
     */
    public Runnable addItemCustody(ItemCustody custody) {
        custodies.add(custody);
        return () -> custodies.remove(custody);
    }

    /**
     * Main thread: items modules are holding for this player. Provider errors are logged and
     * skipped.
     */
    public List<ItemStack> custody(Player player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemCustody custody : custodies) {
            try {
                for (ItemStack item : custody.held(player)) {
                    if (item != null && !item.isEmpty()) {
                        out.add(item.clone());
                    }
                }
            } catch (RuntimeException e) {
                log.error("맡은 아이템 조회 오류 (무시): {}", player.getName(), e);
            }
        }
        return out;
    }

    /**
     * Registers a pre-move hook (docs 20 0.2 HandoffBlocker). Call the returned Runnable to remove
     * it.
     */
    public Runnable addHandoffBlocker(HandoffBlocker blocker) {
        return handoffs.addHandoffBlocker(blocker);
    }

    // ------------------------------------------------------------------ Saving

    /**
     * Whether the move snapshot was taken and actions must be blocked (main thread). Corresponds to
     * HuskSync's {@code isLocked}. On join the data is loaded before login and applied right in the
     * join event, so there is no separate blocked window.
     */
    @Override
    public boolean frozen(UUID player) {
        PlayerState state = online.get(player);
        return state != null && state.handoffSaved();
    }

    /** Listener that blocks actions while {@link #frozen} (registered by Core). */
    public Listener freezeListener() {
        return new HandoffFreeze(this);
    }

    private void autosave() {
        for (PlayerState state : online.values()) {
            if (!state.handoffSaved()) {
                enqueueSave(state, false, SaveReason.AUTOSAVE);
            }
        }
    }

    /**
     * Main thread: enqueue every online player and write right away (before maintenance). Locks are
     * kept.
     */
    @Override
    public CompletableFuture<Void> saveNow(UUID uuid) {
        PlayerState state = online.get(uuid);
        if (state == null || state.handoffSaved()) {
            // Once the move snapshot is taken the lock passes to the destination: writing again
            // could overwrite the destination's data
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

    @Override
    public void saveSoon(UUID uuid) {
        if (!saveSoonPending.add(uuid)) {
            return;
        }
        long delay = Math.max(1, saveSoonDelay.getAsLong());
        if (!acceptingLogins) {
            // Shutting down: shutdown() saves everyone (a disabled plugin can't schedule tasks)
            saveSoonPending.remove(uuid);
            return;
        }
        var _ =
                tasks.later(
                        delay,
                        () -> {
                            saveSoonPending.remove(uuid);
                            PlayerState state = online.get(uuid);
                            if (state == null || state.handoffSaved()) {
                                // The quit or move save already writes the state at that point
                                return;
                            }
                            var _ =
                                    saveNow(uuid)
                                            .exceptionally(
                                                    e -> {
                                                        // Stays in the queue and is written on the
                                                        // next flush
                                                        log.warn(
                                                                "거래 뒤 저장 실패: {} ({})",
                                                                uuid,
                                                                String.valueOf(e.getMessage()));
                                                        return null;
                                                    });
                        });
    }

    /** {@link #saveSoon} delay (ticks). InventorySync sets it from balance.yml. */
    public void setSaveSoonDelay(LongSupplier ticks) {
        this.saveSoonDelay = ticks;
    }

    private void saveAllNow() {
        autosave();
        var _ = tasks.async(queue::flush);
    }

    /** Main thread: runs the save hooks, then copies the state into the write-behind queue. */
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

    /** Server shutdown: block new logins → save online players → the caller flushes the queue. */
    public void shutdown() {
        acceptingLogins = false;
        List<UUID> players = new ArrayList<>(online.keySet());
        for (PlayerState state : online.values()) {
            if (!state.handoffSaved()) {
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
        quits.flushUnsavedOnShutdown();
        online.clear();
    }

    private void refreshLocks() {
        Set<UUID> unsavedQuits = quits.unsavedQuits();
        if (!unsavedQuits.isEmpty()) {
            quits.retryUnsavedQuits();
        }
        if (online.isEmpty() && unsavedQuits.isEmpty()) {
            return;
        }
        List<UUID> owned = new ArrayList<>(unsavedQuits);
        for (PlayerState state : online.values()) {
            // Still extended while waiting on a handoff blocker delay: the lock still belongs to
            // this server (owned)
            if (!state.handoffSaved()) {
                owned.add(state.uuid());
            }
        }
        var _ = tasks.async(() -> locks.refresh(owned, infra.serverId()));
    }

    /** Cleans up players loaded before login who never joined. */
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

    static String prefix(UUID uuid) {
        return "player:" + uuid + ":";
    }
}
