package wiki.creeper.harbor.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ServerHeartbeat;
import wiki.creeper.harbor.common.redis.MessageBus;

/** 백엔드 하트비트 표 (docs 20 1.6: 15초 무응답이면 입장 대상에서 제외, 60초 무응답이면 운영진 알림). */
final class ServerDirectory {

    static final Duration STALE = Duration.ofSeconds(15);

    /** 도착 뒤 이만큼 지나서 받은 하트비트부터 그 사람이 online에 들어 있다고 본다 (보낸 시각과 받은 시각 차이). */
    private static final Duration ARRIVAL_SLACK = Duration.ofSeconds(1);

    /** 하트비트가 끊겨도 진행 중 이동이 영영 남지 않게 (준비 5초 + 연결보다 넉넉히). */
    private static final Duration PENDING_MAX = Duration.ofSeconds(30);

    /** 마지막 하트비트. */
    record Entry(ServerHeartbeat beat, Instant receivedAt) {
        boolean fresh(Instant now) {
            return Duration.between(receivedAt, now).compareTo(STALE) < 0;
        }
    }

    /** 이동 중이거나 도착했지만 아직 하트비트 online에 안 잡힌 사람. arrivedAt이 null이면 이동 중. */
    private record Pending(String server, Instant startedAt, @Nullable Instant arrivedAt) {}

    private final ProxyConfig config;
    private final Map<String, Entry> servers = new ConcurrentHashMap<>();
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile @Nullable Integer capOverride;

    ServerDirectory(ProxyConfig config) {
        this.config = config;
    }

    void start(MessageBus bus) {
        bus.subscribe(
                ProxyMessages.HEARTBEATS,
                envelope -> {
                    String id = envelope.payload().serverId();
                    Instant now = Instant.now();
                    servers.put(id, new Entry(envelope.payload(), now));
                    // 이 하트비트의 online에는 그 전에 도착한 사람이 들어 있다
                    pending.values()
                            .removeIf(
                                    p -> {
                                        Instant arrived = p.arrivedAt();
                                        return p.server().equals(id)
                                                && arrived != null
                                                && arrived.plus(ARRIVAL_SLACK).isBefore(now);
                                    });
                });
    }

    /** 이동 시작: 하트비트가 5초마다라 그 사이 보낸 사람을 빈자리에서 빼야 한도를 넘지 않는다. */
    void moveStarted(UUID player, String server) {
        pending.put(player, new Pending(server, Instant.now(), null));
    }

    /** 이동 끝: 실패면 지우고, 성공이면 다음 하트비트가 반영할 때까지 남긴다. */
    void moveFinished(UUID player, String server, boolean ok) {
        if (!ok) {
            Pending current = pending.get(player);
            if (current != null && current.server().equals(server)) {
                pending.remove(player, current);
            }
            return;
        }
        Instant now = Instant.now();
        pending.computeIfPresent(
                player,
                (k, p) -> p.server().equals(server) ? new Pending(server, p.startedAt(), now) : p);
    }

    /** 하트비트 online에 아직 안 잡힌 이동 인원. */
    int pending(String serverId) {
        Instant cutoff = Instant.now().minus(PENDING_MAX);
        pending.values().removeIf(p -> p.startedAt().isBefore(cutoff));
        int count = 0;
        for (Pending p : pending.values()) {
            if (p.server().equals(serverId)) {
                count++;
            }
        }
        return count;
    }

    /** /hp queue cap: 본 서버 수용 한도를 임시로 바꾼다 (null이면 설정값). */
    void setCapOverride(@Nullable Integer cap) {
        capOverride = cap;
    }

    /** 수용 한도: 본 서버는 설정값(또는 임시 한도), 그 밖은 서버가 알린 최대 인원. 서버 최대 인원을 넘지는 않는다. */
    int capacity(String serverId, ServerHeartbeat beat) {
        Integer configured = config.mainServers().get(serverId);
        Integer override = capOverride;
        if (configured == null) {
            return beat.capacity();
        }
        return Math.min(override != null ? override : configured, beat.capacity());
    }

    Optional<Entry> get(String serverId) {
        return Optional.ofNullable(servers.get(serverId));
    }

    Map<String, Entry> all() {
        return Map.copyOf(servers);
    }

    /** 하트비트가 살아 있고 입장을 받는지 (수용 한도는 보지 않음: 예약 자리 귀환·인스턴스 배정 확인용). */
    boolean isAlive(String serverId) {
        Entry entry = servers.get(serverId);
        return entry != null && entry.fresh(Instant.now()) && entry.beat().accepting();
    }

    /**
     * 오래 끊긴 서버를 한 번씩 알린다 (복구되면 다시 알릴 수 있게 표시를 지운다).
     *
     * @param alert (서버 ID, 마지막 수신 뒤 초)
     */
    void checkStale(Duration threshold, BiConsumer<String, Long> alert) {
        Instant now = Instant.now();
        servers.forEach(
                (id, entry) -> {
                    long age = Duration.between(entry.receivedAt(), now).toSeconds();
                    if (age >= threshold.toSeconds()) {
                        if (alerted.add(id)) {
                            alert.accept(id, age);
                        }
                    } else {
                        alerted.remove(id);
                    }
                });
    }

    /** 입장 가능한지: 하트비트가 살아 있고, 입장을 받고, 자리가 있는지 (진행 중 이동 포함). */
    boolean canAccept(String serverId, int extra) {
        Entry entry = servers.get(serverId);
        if (entry == null || !entry.fresh(Instant.now())) {
            return false;
        }
        ServerHeartbeat beat = entry.beat();
        return beat.accepting()
                && beat.online() + pending(serverId) + extra <= capacity(serverId, beat);
    }
}
