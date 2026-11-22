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

/**
 * Backend heartbeat table (docs 20 1.6: excluded from entry after 15 seconds without response,
 * staff alerted after 60 seconds).
 */
final class ServerDirectory {

    static final Duration STALE = Duration.ofSeconds(15);

    /**
     * Heartbeats received at least this long after arrival are assumed to include that person in
     * online (gap between send and receive time).
     */
    private static final Duration ARRIVAL_SLACK = Duration.ofSeconds(1);

    /**
     * So in-progress moves do not linger forever if heartbeats stop (comfortably more than 5
     * seconds of preparation + connect).
     */
    private static final Duration PENDING_MAX = Duration.ofSeconds(30);

    /** Last heartbeat. */
    record Entry(ServerHeartbeat beat, Instant receivedAt) {
        boolean fresh(Instant now) {
            return Duration.between(receivedAt, now).compareTo(STALE) < 0;
        }
    }

    /**
     * People moving, or arrived but not yet counted in heartbeat online. arrivedAt null means still
     * moving.
     */
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
                    // This heartbeat's online includes people who arrived before then
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

    /**
     * Move start: heartbeats come every 5 seconds, so people sent in between must be subtracted
     * from free seats to stay within the limit.
     */
    void moveStarted(UUID player, String server) {
        pending.put(player, new Pending(server, Instant.now(), null));
    }

    /** Move end: removed on failure; on success kept until the next heartbeat reflects it. */
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

    /** Moving players not yet counted in heartbeat online. */
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

    /**
     * /hp queue cap: temporarily changes the main server capacity (null means the configured
     * value).
     */
    void setCapOverride(@Nullable Integer cap) {
        capOverride = cap;
    }

    /**
     * Capacity: the configured value (or temporary cap) for main servers, otherwise the max players
     * the server reported. Never exceeds the server's max players.
     */
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

    /**
     * Whether the heartbeat is alive and entry is accepted (capacity not checked: for reserved-seat
     * returns and instance assignment checks).
     */
    boolean isAlive(String serverId) {
        Entry entry = servers.get(serverId);
        return entry != null && entry.fresh(Instant.now()) && entry.beat().accepting();
    }

    /**
     * Alerts once about servers that have been down long (clears the mark on recovery so it can
     * alert again).
     *
     * @param alert (server ID, seconds since last received)
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

    /**
     * Whether entry is possible: heartbeat alive, accepting entries, and a free seat (including
     * in-progress moves).
     */
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
