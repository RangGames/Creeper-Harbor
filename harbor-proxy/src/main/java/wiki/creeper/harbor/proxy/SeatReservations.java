package wiki.creeper.harbor.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Main server seats of people who went to instances (docs 20 1.4 instance assignment: "returns skip
 * the queue (seat reservation)").
 *
 * <ul>
 *   <li>When moving from the main server to an instance, the source main server seat is held, and
 *       the queue counts that many fewer free seats
 *   <li>Return moves to that main server are let in even over capacity
 *   <li>Released on re-entering the main server. If the connection drops in an instance, kept for
 *       the reconnect grace period (so they can return via the Boss rejoin key)
 * </ul>
 */
final class SeatReservations {

    private record Seat(String server, @Nullable Instant offlineSince) {}

    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();

    void reserve(UUID player, String mainServer) {
        seats.put(player, new Seat(mainServer, null));
    }

    Optional<String> seatOf(UUID player) {
        return Optional.ofNullable(seats.get(player)).map(Seat::server);
    }

    /** true for a return to this main server (skips the capacity check). */
    boolean holds(UUID player, String server) {
        Seat seat = seats.get(player);
        return seat != null && seat.server().equals(server);
    }

    int reservedOn(String mainServer) {
        return (int) seats.values().stream().filter(s -> s.server().equals(mainServer)).count();
    }

    Optional<String> release(UUID player) {
        return Optional.ofNullable(seats.remove(player)).map(Seat::server);
    }

    /** Disconnected from an instance: keeps the seat for the grace period. */
    void markOffline(UUID player, Instant now) {
        seats.computeIfPresent(player, (k, seat) -> new Seat(seat.server(), now));
    }

    void markOnline(UUID player) {
        seats.computeIfPresent(player, (k, seat) -> new Seat(seat.server(), null));
    }

    /** Releases offline seats past the grace period. */
    void expire(Instant now, Duration grace) {
        seats.entrySet()
                .removeIf(
                        e -> {
                            Instant since = e.getValue().offlineSince();
                            return since != null
                                    && Duration.between(since, now).compareTo(grace) > 0;
                        });
    }
}
