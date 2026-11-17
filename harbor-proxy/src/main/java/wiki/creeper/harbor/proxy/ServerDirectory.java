package wiki.creeper.harbor.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ServerHeartbeat;
import wiki.creeper.harbor.common.redis.MessageBus;

/** 백엔드 하트비트 표 (docs 20 1.6: 15초 무응답이면 입장 대상에서 제외). */
final class ServerDirectory {

    static final Duration STALE = Duration.ofSeconds(15);

    /** 마지막 하트비트. */
    record Entry(ServerHeartbeat beat, Instant receivedAt) {
        boolean fresh(Instant now) {
            return Duration.between(receivedAt, now).compareTo(STALE) < 0;
        }
    }

    private final Map<String, Entry> servers = new ConcurrentHashMap<>();

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
