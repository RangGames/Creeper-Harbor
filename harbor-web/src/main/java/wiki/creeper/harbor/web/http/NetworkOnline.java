package wiki.creeper.harbor.web.http;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network-wide online count for GET /v1/server: the sum of the latest backend heartbeat
 * (ProxyMessages.HEARTBEATS, every 5 seconds per server) of each server seen within {@link
 * #STALE_MILLIS}. Same approach as Economy MarketEngine.networkOnline. Thread-safe, no server API.
 */
public final class NetworkOnline {

    /** Beats older than this are ignored (server stopped or Redis cut). */
    public static final long STALE_MILLIS = 15_000;

    private record Beat(int online, long atMillis) {}

    private final Map<String, Beat> beats = new ConcurrentHashMap<>();

    public void beat(String serverId, int online, long nowMillis) {
        beats.put(serverId, new Beat(Math.max(0, online), nowMillis));
    }

    /** Fresh total and server count; empty when no server has sent a fresh beat. */
    public Snapshot snapshot(long nowMillis) {
        int sum = 0;
        int servers = 0;
        for (Beat b : beats.values()) {
            if (nowMillis - b.atMillis() <= STALE_MILLIS) {
                sum += b.online();
                servers++;
            }
        }
        return new Snapshot(servers == 0 ? OptionalInt.empty() : OptionalInt.of(sum), servers);
    }

    /**
     * @param online network total (empty without fresh beats)
     * @param servers number of servers with a fresh beat
     */
    public record Snapshot(OptionalInt online, int servers) {}
}
