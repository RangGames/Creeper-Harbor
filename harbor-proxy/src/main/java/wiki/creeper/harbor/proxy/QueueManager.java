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
import wiki.creeper.harbor.common.proxy.ProxyMessages.PackStatus;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * 본 서버 입장 대기열 (docs 20 1.4 QueueManager).
 *
 * <ul>
 *   <li>줄 3개: 재접속 유예 → 우선(후원자·CBT 운영진) → 일반. 일반 줄이 굶지 않도록 입장 N명 중 최소 1명은 일반 줄에서 ({@link
 *       QueueLines})
 *   <li>로비 Core가 PackStatus LOADED를 보내면, Boss 재접속 키가 있으면 인스턴스로, 인스턴스 귀환 자리가 있으면 그 본 서버로 바로 보내고, 아니면
 *       줄에 넣는다
 *   <li>후원 등급(Common DonorTier)은 로그인 때 조회해 둔다. HARBOR 이상이면 우선 줄
 *   <li>이전 서버 잠금이 남아 있으면(비정상 종료, 최대 30초) 유예 줄 맨 앞에 "정리 중"으로 세우고 잠금이 풀리면 입장
 *   <li>1초마다 빈자리만큼, 최대 per_second명 입장. TPS가 낮으면 한도를 현재 인원으로 동결. Redis가 끊기면 입장 중단
 *   <li>로비 보스바에 순번·예상 대기 시간 (5초마다)
 * </ul>
 *
 * 상태는 프록시 메모리에만 둔다 (프록시 1대). 줄 조작은 이 객체로 동기화한다.
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

    /** 이전 서버 세션 잠금이 남아 있는 사람 ({@link StaleLockWatcher}가 채우고 비운다). */
    private final Set<UUID> cleaning = ConcurrentHashMap.newKeySet();

    private final Deque<Instant> recentAdmissions = new ArrayDeque<>();
    private boolean paused;
    private @Nullable Integer capOverride;
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
     * @param rejoin Boss 재접속 키 확인 (옮겼으면 true)
     */
    void start(MessageBus bus, Function<Player, CompletableFuture<Boolean>> rejoin) {
        bus.subscribe(
                ProxyMessages.toProxy(PackStatus.class),
                envelope -> {
                    if (envelope.payload().status() != PackStatus.Status.LOADED) {
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
                                                                    if (!moved) {
                                                                        enqueue(player);
                                                                    }
                                                                });
                                    });
                });
    }

    /** 로그인 때 조회한 후원 등급. */
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
        // 인스턴스에서 끊겼다 돌아온 사람: 예약해 둔 본 서버 자리로 바로 (대기열 없음)
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

    /** 본 서버에서 나간 사람: 유예 시간 안에 돌아오면 자리를 보장한다. */
    synchronized void grantGrace(UUID uuid) {
        graceUntil.put(uuid, Instant.now().plusSeconds(config.queue().graceSeconds()));
    }

    /** 입장 실패: 맨 앞으로 되돌린다 (이전 접속 정리 중 등). */
    synchronized void requeueFront(UUID uuid) {
        if (proxy.getPlayer(uuid).isPresent()) {
            lines.addFront(QueueLines.Line.GRACE, uuid);
        }
    }

    /** 접속 종료 · 로비 이탈: 자리를 바로 지운다 (재접속하면 줄 뒤로, 유예는 본 서버에 있던 사람만). */
    synchronized void remove(UUID uuid) {
        lines.remove(uuid);
        BossBar bar = bars.remove(uuid);
        if (bar != null) {
            proxy.getPlayer(uuid).ifPresent(p -> p.hideBossBar(bar));
        }
    }

    /** 접속 종료 때 메모리 정리. */
    void forget(UUID uuid) {
        remove(uuid);
        donors.remove(uuid);
        cleaning.remove(uuid);
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

    synchronized void setCap(@Nullable Integer cap) {
        capOverride = cap;
    }

    /** 1초마다 실행한다. 메모리 연산만 한다. */
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
            if (player.isEmpty()) {
                continue;
            }
            admitted++;
            recentAdmissions.addLast(Instant.now());
            BossBar bar = bars.remove(next);
            if (bar != null) {
                player.get().hideBossBar(bar);
            }
            var _ =
                    mover.move(player.get(), server.get(), "queue")
                            .thenAccept(
                                    result -> {
                                        if (!result.ok()) {
                                            requeueFront(next);
                                        }
                                    });
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
        int capacity =
                capOverride != null
                        ? capOverride
                        : config.mainServers().getOrDefault(server, beat.capacity());
        // TPS 보호: 18 아래면 현재 인원으로 동결, 19.5 이상 1분 유지되면 해제
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
        // 인스턴스에 간 사람의 자리는 비워 둔다
        return Math.max(0, capacity - beat.online() - seats.reservedOn(server));
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
