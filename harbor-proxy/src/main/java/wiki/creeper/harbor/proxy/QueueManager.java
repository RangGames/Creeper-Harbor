package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.DepartRequest;
import wiki.creeper.harbor.common.proxy.ProxyMessages.PackStatus;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * Main server entry queue (docs 20 1.4 QueueManager).
 *
 * <ul>
 *   <li>3 lines: reconnect grace → priority (donors, CBT staff) → normal. So the normal line does
 *       not starve, at least 1 of every N entries comes from the normal line ({@link QueueLines})
 *   <li>When the lobby Core sends PackStatus LOADED: if there is a Boss rejoin key, to the
 *       instance; if there is an instance return seat, straight to that main server; otherwise into
 *       a line
 *   <li>Donor tier (Common DonorTier) is looked up at login. HARBOR or higher goes to the priority
 *       line
 *   <li>If a previous server lock remains (crash, up to 30 seconds), they are placed at the front
 *       of the grace line as "cleaning up" and enter once the lock is released
 *   <li>Every second, admit as many as there are free seats, up to per_second. If TPS is low, the
 *       limit is frozen at the current count. Admission stops if Redis is down
 *   <li>Position and estimated wait time on the lobby boss bar (every 5 seconds)
 * </ul>
 *
 * State lives only in proxy memory (a single proxy). Line operations synchronize on this object.
 */
final class QueueManager {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ServerDirectory directory;
    private final MoveCoordinator mover;
    private final SeatReservations seats;
    private final ProxyText text;
    private final BooleanSupplier redisUp;
    private final QueueLines<UUID> lines;
    private final Map<UUID, Instant> graceUntil = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, DonorTier> donors = new ConcurrentHashMap<>();

    /**
     * People whose previous server session lock remains (filled and cleared by {@link
     * StaleLockWatcher}).
     */
    private final Set<UUID> cleaning = ConcurrentHashMap.newKeySet();

    /**
     * People who left via /로비 themselves: not queued even after the pack is applied (until /출항,
     * proposed).
     */
    private final Set<UUID> staying = ConcurrentHashMap.newKeySet();

    private final Deque<Instant> recentAdmissions = new ArrayDeque<>();
    private boolean paused;
    private @Nullable Instant healthySince;
    private boolean frozen;
    private long ticks;

    QueueManager(
            ProxyServer proxy,
            ProxyConfig config,
            ServerDirectory directory,
            MoveCoordinator mover,
            SeatReservations seats,
            ProxyText text,
            BooleanSupplier redisUp) {
        this.proxy = proxy;
        this.config = config;
        this.directory = directory;
        this.mover = mover;
        this.seats = seats;
        this.text = text;
        this.redisUp = redisUp;
        this.lines = new QueueLines<>(config.queue().normalShare());
    }

    /**
     * @param rejoin Boss rejoin key check (true if moved)
     */
    void start(MessageBus bus, Function<Player, CompletableFuture<Boolean>> rejoin) {
        bus.subscribe(
                ProxyMessages.toProxy(PackStatus.class),
                envelope -> {
                    if (envelope.payload().status() != PackStatus.Status.LOADED) {
                        return;
                    }
                    if (staying.contains(envelope.payload().player())) {
                        proxy.getPlayer(envelope.payload().player())
                                .ifPresent(p -> p.sendMessage(text.get("lobby.staying")));
                        return;
                    }
                    proxy.getPlayer(envelope.payload().player())
                            .ifPresent(
                                    player -> {
                                        var _ =
                                                rejoin.apply(player)
                                                        .exceptionally(e -> false)
                                                        .thenAccept(
                                                                moved -> {
                                                                    if (moved) {
                                                                        return;
                                                                    }
                                                                    if (autoJoin(player)) {
                                                                        enqueue(player);
                                                                    } else {
                                                                        staying.add(
                                                                                player
                                                                                        .getUniqueId());
                                                                        player.sendMessage(
                                                                                text.get(
                                                                                        "lobby.staying"));
                                                                    }
                                                                });
                                    });
                });
        bus.subscribe(
                ProxyMessages.toProxy(DepartRequest.class),
                envelope -> proxy.getPlayer(envelope.payload().player()).ifPresent(this::depart));
    }

    /** Donor tier looked up at login. */
    void setDonor(UUID uuid, DonorTier tier) {
        if (tier == DonorTier.NONE) {
            donors.remove(uuid);
        } else {
            donors.put(uuid, tier);
        }
    }

    DonorTier donor(UUID uuid) {
        return donors.getOrDefault(uuid, DonorTier.NONE);
    }

    void markCleaning(UUID uuid) {
        cleaning.add(uuid);
    }

    void clearCleaning(UUID uuid) {
        cleaning.remove(uuid);
    }

    Set<UUID> cleaning() {
        return Set.copyOf(cleaning);
    }

    void enqueue(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.hasPermission(ProxyPerms.QUEUE_BYPASS)) {
            var _ = mover.move(player, mainServer().orElse(firstMain()), "queue_bypass");
            return;
        }
        // Returning after a disconnect from an instance: straight to the reserved main server seat
        // (no queue)
        Optional<String> seat = seats.seatOf(uuid);
        if (seat.isPresent() && directory.isAlive(seat.get()) && !cleaning.contains(uuid)) {
            var _ =
                    mover.move(player, seat.get(), "seat_return")
                            .thenAccept(
                                    result -> {
                                        if (!result.ok()) {
                                            requeueFront(uuid);
                                        }
                                    });
            return;
        }
        synchronized (this) {
            if (lines.contains(uuid)) {
                return;
            }
            Instant grace = graceUntil.remove(uuid);
            if (cleaning.contains(uuid)) {
                lines.addFront(QueueLines.Line.GRACE, uuid);
                return;
            }
            QueueLines.Line line;
            if (grace != null && Instant.now().isBefore(grace)) {
                line = QueueLines.Line.GRACE;
            } else if (config.priority().contains(uuid) || donor(uuid).queuePriority()) {
                line = QueueLines.Line.PRIORITY;
            } else {
                line = QueueLines.Line.NORMAL;
            }
            lines.add(line, uuid);
        }
    }

    /** Stay in the lobby: remove from the queue and block auto-entry after the pack is applied. */
    void stay(UUID uuid) {
        staying.add(uuid);
        remove(uuid);
    }

    /**
     * Whether to queue right after the pack is applied. Even with queue.auto_join off, people
     * returning after an instance disconnect (seat reserved) and people whose previous session is
     * being cleaned up are queued automatically.
     */
    private boolean autoJoin(Player player) {
        UUID uuid = player.getUniqueId();
        return config.queue().autoJoin()
                || seats.seatOf(uuid).isPresent()
                || cleaning.contains(uuid);
    }

    /** Departure result. */
    enum Departure {
        QUEUED,
        ALREADY_QUEUED,
        NOT_IN_LOBBY
    }

    /** /출항 · DepartRequest: if in the lobby, ends the stay and queues the player. */
    Departure depart(Player player) {
        String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
        if (!server.equals(config.lobby())) {
            player.sendMessage(text.get("lobby.only"));
            return Departure.NOT_IN_LOBBY;
        }
        leaveStay(player.getUniqueId());
        if (position(player.getUniqueId()).isPresent()) {
            player.sendMessage(text.get("lobby.already_queued"));
            return Departure.ALREADY_QUEUED;
        }
        enqueue(player);
        player.sendMessage(text.get("lobby.depart"));
        return Departure.QUEUED;
    }

    /**
     * Right before going to the lobby at a backend's request (AFK, Skript, NPC): do not auto-depart
     * on arrival.
     */
    void beforeLobbyMove(UUID uuid) {
        stay(uuid);
    }

    /**
     * Lobby move result: on failure clears the mark; if they left the main server, grants seat
     * grace.
     */
    void afterLobbyMove(UUID uuid, String origin, boolean ok) {
        if (!ok) {
            leaveStay(uuid);
        } else if (config.mainServers().containsKey(origin)) {
            grantGrace(uuid);
        }
    }

    /** Ends the stay. true if they were staying. */
    boolean leaveStay(UUID uuid) {
        return staying.remove(uuid);
    }

    /**
     * Someone who left the main server: their seat is guaranteed if they return within the grace
     * period.
     */
    synchronized void grantGrace(UUID uuid) {
        graceUntil.put(uuid, Instant.now().plusSeconds(config.queue().graceSeconds()));
    }

    /** Entry failed: put back at the front (previous session still being cleaned up, etc.). */
    synchronized void requeueFront(UUID uuid) {
        if (proxy.getPlayer(uuid).isPresent()) {
            lines.addFront(QueueLines.Line.GRACE, uuid);
        }
    }

    /**
     * Disconnect or leaving the lobby: removes the place immediately (reconnecting goes to the
     * back; grace only for those who were on the main server).
     */
    synchronized void remove(UUID uuid) {
        lines.remove(uuid);
        BossBar bar = bars.remove(uuid);
        if (bar != null) {
            proxy.getPlayer(uuid).ifPresent(p -> p.hideBossBar(bar));
        }
    }

    /** Memory cleanup on disconnect. */
    void forget(UUID uuid) {
        remove(uuid);
        donors.remove(uuid);
        cleaning.remove(uuid);
        staying.remove(uuid);
    }

    synchronized OptionalInt position(UUID uuid) {
        return lines.position(uuid);
    }

    synchronized int size() {
        return lines.size();
    }

    synchronized List<UUID> snapshot() {
        return lines.snapshot();
    }

    /** /hp queue move. */
    synchronized boolean moveTo(UUID uuid, int position) {
        return lines.moveTo(uuid, position);
    }

    synchronized void setPaused(boolean value) {
        paused = value;
    }

    void setCap(@Nullable Integer cap) {
        directory.setCapOverride(cap);
    }

    /** Runs every second. Memory operations only. */
    synchronized void tick() {
        ticks++;
        if (!paused && redisUp.getAsBoolean()) {
            admit();
        }
        if (ticks % 5 == 0) {
            updateBars();
        }
        if (ticks % 60 == 0) {
            Instant now = Instant.now();
            graceUntil.values().removeIf(until -> until.isBefore(now));
            seats.expire(now, Duration.ofSeconds(config.queue().graceSeconds()));
        }
    }

    private void admit() {
        Optional<String> server = mainServer();
        if (server.isEmpty()) {
            return;
        }
        int free = freeSlots(server.get());
        int budget = Math.min(free, config.queue().perSecond());
        int admitted = 0;
        while (admitted < budget) {
            UUID next = lines.pollNext(uuid -> !cleaning.contains(uuid));
            if (next == null) {
                return;
            }
            Optional<Player> player = proxy.getPlayer(next);
            if (player.isEmpty() || !inLobby(player.get())) {
                // Left the lobby by another route: drop them so they are not pulled out of an
                // instance or bounced back via already_there
                hideBar(next, player);
                continue;
            }
            admitted++;
            recentAdmissions.addLast(Instant.now());
            hideBar(next, player);
            var _ =
                    mover.move(player.get(), server.get(), "queue")
                            .thenAccept(
                                    result -> {
                                        // Already on the main server, no need to requeue (it would
                                        // block the front every tick)
                                        if (!result.ok()
                                                && !"already_there".equals(result.reason())) {
                                            requeueFront(next);
                                        }
                                    });
        }
    }

    private boolean inLobby(Player player) {
        return player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .filter(config.lobby()::equals)
                .isPresent();
    }

    private void hideBar(UUID uuid, Optional<Player> player) {
        BossBar bar = bars.remove(uuid);
        if (bar != null) {
            player.ifPresent(p -> p.hideBossBar(bar));
        }
    }

    private int freeSlots(String server) {
        Optional<ServerDirectory.Entry> entry = directory.get(server);
        if (entry.isEmpty()
                || !entry.get().fresh(Instant.now())
                || !entry.get().beat().accepting()) {
            return 0;
        }
        var beat = entry.get().beat();
        int capacity = directory.capacity(server, beat);
        // TPS protection: below 18, freeze at the current count; lifted after staying at 19.5+ for
        // 1 minute
        Instant now = Instant.now();
        if (beat.tps() < config.queue().tpsFreeze()) {
            frozen = true;
            healthySince = null;
        } else if (beat.tps() >= config.queue().tpsResume()) {
            if (healthySince == null) {
                healthySince = now;
            } else if (Duration.between(healthySince, now).toSeconds() >= 60) {
                frozen = false;
            }
        }
        if (frozen) {
            return 0;
        }
        // Keep seats of people who went to instances free. People sent after the heartbeat (5
        // seconds) are not in online yet, so subtract them
        return Math.max(
                0, capacity - beat.online() - directory.pending(server) - seats.reservedOn(server));
    }

    private Optional<String> mainServer() {
        return config.mainServers().keySet().stream()
                .filter(s -> directory.canAccept(s, 1))
                .findFirst();
    }

    private String firstMain() {
        return config.mainServers().keySet().iterator().next();
    }

    private void updateBars() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        while (!recentAdmissions.isEmpty() && recentAdmissions.peekFirst().isBefore(cutoff)) {
            recentAdmissions.pollFirst();
        }
        double perSecond = recentAdmissions.size() / 600.0;
        int position = 1;
        for (UUID uuid : lines.snapshot()) {
            int pos = position++;
            Optional<Player> online = proxy.getPlayer(uuid);
            if (online.isEmpty()) {
                continue;
            }
            Player player = online.get();
            Component name =
                    cleaning.contains(uuid)
                            ? text.get("queue.cleaning")
                            : text.get(
                                    "queue.bar",
                                    Placeholder.unparsed("position", String.valueOf(pos)),
                                    Placeholder.unparsed("eta", eta(pos, perSecond)));
            BossBar bar =
                    bars.computeIfAbsent(
                            uuid,
                            k -> {
                                BossBar created =
                                        BossBar.bossBar(
                                                name,
                                                1f,
                                                BossBar.Color.YELLOW,
                                                BossBar.Overlay.PROGRESS);
                                player.showBossBar(created);
                                return created;
                            });
            bar.name(name);
        }
    }

    synchronized String eta(int position) {
        return eta(position, recentAdmissions.size() / 600.0);
    }

    private static String eta(int position, double perSecond) {
        if (perSecond <= 0) {
            return "?";
        }
        long seconds = Math.round(position / perSecond);
        return seconds < 60 ? seconds + "초" : (seconds / 60) + "분";
    }
}
