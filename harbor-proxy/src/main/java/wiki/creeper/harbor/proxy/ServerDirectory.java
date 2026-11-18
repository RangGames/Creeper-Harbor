package wiki.creeper.harbor.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ServerHeartbeat;
import wiki.creeper.harbor.common.redis.MessageBus;

/** 백엔드 하트비트 표 (docs 20 1.6: 15초 무응답이면 입장 대상에서 제외, 60초 무응답이면 운영진 알림). */
final class ServerDirectory {

    static final Duration STALE = Duration.ofSeconds(15);

    /** 마지막 하트비트. */
    record Entry(ServerHeartbeat beat, Instant receivedAt) {
        boolean fresh(Instant now) {
            return Duration.between(receivedAt, now).compareTo(STALE) < 0;
        }
    }

    private final Map<String, Entry> servers = new ConcurrentHashMap<>();
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();

    void start(MessageBus bus) {
        bus.subscribe(
                ProxyMessages.HEARTBEATS,
                envelope ->
                        servers.put(
                                envelope.payload().serverId(),
                                new Entry(envelope.payload(), Instant.now())));
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

    /** 입장 가능한지: 하트비트가 살아 있고, 입장을 받고, 자리가 있는지. */
    boolean canAccept(String serverId, int extra) {
        Entry entry = servers.get(serverId);
        if (entry == null || !entry.fresh(Instant.now())) {
            return false;
        }
        ServerHeartbeat beat = entry.beat();
        return beat.accepting() && beat.online() + extra <= beat.capacity();
    }
}
