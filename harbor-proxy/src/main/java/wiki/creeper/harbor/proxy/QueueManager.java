package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.PackStatus;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * 본 서버 입장 대기열 (docs 20 1.4 QueueManager).
 *
 * <ul>
 *   <li>줄 3개: 재접속 유예 → 우선 → 일반. 일반 줄이 굶지 않도록 입장 N명 중 최소 1명은 일반 줄에서
 *   <li>로비 Core가 PackStatus LOADED를 보내면 줄에 넣는다
 *   <li>1초마다 빈자리만큼, 최대 per_second명 입장. TPS가 낮으면 한도를 현재 인원으로 동결
 *   <li>로비 보스바에 순번·예상 대기 시간 (5초마다)
 * </ul>
 *
 * 상태는 프록시 메모리에만 둔다 (프록시 1대). 모든 메서드는 이 객체로 동기화한다.
 */
final class QueueManager {

    private enum Line {
        GRACE,
        PRIORITY,
        NORMAL
    }

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ServerDirectory directory;
    private final MoveCoordinator mover;
    private final ProxyText text;
    private final Map<Line, Deque<UUID>> lines = new EnumMap<>(Line.class);
    private final Map<UUID, Instant> graceUntil = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Deque<Instant> recentAdmissions = new ArrayDeque<>();
    private int admittedSinceNormal;
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
            ProxyText text) {
        this.proxy = proxy;
        this.config = config;
        this.directory = directory;
        this.mover = mover;
        this.text = text;
        for (Line line : Line.values()) {
            lines.put(line, new ArrayDeque<>());
        }
    }

    void start(MessageBus bus) {
        bus.subscribe(
                ProxyMessages.toProxy(PackStatus.class),
                envelope -> {
                    if (envelope.payload().status() == PackStatus.Status.LOADED) {
                        proxy.getPlayer(envelope.payload().player()).ifPresent(this::enqueue);
                    }
                });
    }

    synchronized void enqueue(Player player) {
        UUID uuid = player.getUniqueId();
        if (position(uuid).isPresent()) {
            return;
        }
        if (player.hasPermission("harbor.proxy.queue.bypass")) {
            var _ = mover.move(player, mainServer().orElse(firstMain()), "queue_bypass");
            return;
        }
        Instant grace = graceUntil.remove(uuid);
        Line line =
                grace != null && Instant.now().isBefore(grace)
                        ? Line.GRACE
                        : config.priority().contains(uuid) ? Line.PRIORITY : Line.NORMAL;
        queue(line).addLast(uuid);
    }

    /** 본 서버에서 나간 사람: 유예 시간 안에 돌아오면 자리를 보장한다. */
    synchronized void grantGrace(UUID uuid) {
        graceUntil.put(uuid, Instant.now().plusSeconds(config.queue().graceSeconds()));
    }

    /** 입장 실패: 맨 앞으로 되돌린다 (이전 접속 정리 중 등). */
    synchronized void requeueFront(UUID uuid) {
        queue(Line.GRACE).addFirst(uuid);
    }

    synchronized void remove(UUID uuid) {
        lines.values().forEach(q -> q.remove(uuid));
        BossBar bar = bars.remove(uuid);
        if (bar != null) {
            proxy.getPlayer(uuid).ifPresent(p -> p.hideBossBar(bar));
        }
    }

    synchronized OptionalInt position(UUID uuid) {
        int index = 1;
        for (Line line : Line.values()) {
            for (UUID queued : queue(line)) {
                if (queued.equals(uuid)) {
                    return OptionalInt.of(index);
                }
                index++;
            }
        }
        return OptionalInt.empty();
    }

    synchronized List<UUID> snapshot() {
        List<UUID> all = new ArrayList<>();
        for (Line line : Line.values()) {
            all.addAll(queue(line));
        }
        return all;
    }

    synchronized void setPaused(boolean value) {
        paused = value;
    }

    synchronized void setCap(@Nullable Integer cap) {
        capOverride = cap;
    }

    /** 1초마다 실행한다. */
    synchronized void tick() {
        ticks++;
        if (!paused) {
            admit();
        }
        if (ticks % 5 == 0) {
            updateBars();
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
            UUID next = pollNext();
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

    /** 재접속 유예 → 우선 → 일반. 단 normal_share명 중 최소 1명은 일반 줄. */
    private @Nullable UUID pollNext() {
        Deque<UUID> normal = queue(Line.NORMAL);
        boolean forceNormal =
                admittedSinceNormal >= config.queue().normalShare() - 1 && !normal.isEmpty();
        if (!forceNormal) {
            for (Line line : List.of(Line.GRACE, Line.PRIORITY)) {
                UUID next = queue(line).pollFirst();
                if (next != null) {
                    admittedSinceNormal++;
                    return next;
                }
            }
        }
        UUID next = normal.pollFirst();
        if (next != null) {
            admittedSinceNormal = 0;
        }
        return next;
    }

    private Deque<UUID> queue(Line line) {
        return Objects.requireNonNull(lines.get(line));
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
        return Math.max(0, capacity - beat.online());
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
        for (UUID uuid : snapshot()) {
            int pos = position++;
            proxy.getPlayer(uuid)
                    .ifPresent(
                            player -> {
                                String eta = eta(pos, perSecond);
                                var name =
                                        text.get(
                                                "queue.bar",
                                                Placeholder.unparsed(
                                                        "position", String.valueOf(pos)),
                                                Placeholder.unparsed("eta", eta));
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
                            });
        }
    }

    String eta(int position) {
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
