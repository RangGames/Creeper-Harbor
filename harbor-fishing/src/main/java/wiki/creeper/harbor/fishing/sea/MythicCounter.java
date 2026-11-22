package wiki.creeper.harbor.fishing.sea;

import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.db.Database;

/**
 * Mythic server count counter (docs 20 3.2, the Redis key harbor:mythic:<event>:<species> in the
 * docs). Core does not expose Redis atomic operations to other modules, so a conditional UPDATE on
 * the DB table fishing_mythic_counter (proposed) achieves the same effect (reserve with INCR, fail
 * when over the limit, DECR on failure). All servers see the same DB, so it is atomic across
 * servers too. If Core gains a Redis counter API, only this class changes.
 */
public final class MythicCounter {

    private final Database database;

    public MythicCounter(Database database) {
        this.database = database;
    }

    /** Reserves one. False if over the limit. */
    public CompletableFuture<Boolean> reserve(String eventId, String fishId, int cap) {
        return database.async(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT IGNORE INTO fishing_mythic_counter (event_id, fish_id, count) VALUES (?, ?, 0)")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE fishing_mythic_counter SET count = count + 1"
                                            + " WHERE event_id = ? AND fish_id = ? AND count < ?")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        ps.setInt(3, cap);
                        return ps.executeUpdate() == 1;
                    }
                });
    }

    /** Returns a reservation (minigame failure, early reel, abort). */
    public CompletableFuture<Void> release(String eventId, String fishId) {
        return database.asyncVoid(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE fishing_mythic_counter SET count = count - 1"
                                            + " WHERE event_id = ? AND fish_id = ? AND count > 0")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        ps.executeUpdate();
                    }
                });
    }

    /** For server shutdown: waits and returns one reservation. Failures are only logged. */
    public void releaseNow(String eventId, String fishId) {
        try {
            database.txVoid(
                    c -> {
                        try (PreparedStatement ps =
                                c.prepareStatement(
                                        "UPDATE fishing_mythic_counter SET count = count - 1"
                                                + " WHERE event_id = ? AND fish_id = ? AND count > 0")) {
                            ps.setString(1, eventId);
                            ps.setString(2, fishId);
                            ps.executeUpdate();
                        }
                    });
        } catch (java.sql.SQLException e) {
            System.getLogger("Harbor/Fishing")
                    .log(
                            System.Logger.Level.WARNING,
                            "신화 예약 반납 실패 (종료 중): " + eventId + " " + fishId,
                            e);
        }
    }

    /** Current count (admin command). */
    public CompletableFuture<Integer> count(String eventId, String fishId) {
        return database.async(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT count FROM fishing_mythic_counter WHERE event_id = ? AND fish_id = ?")) {
                        ps.setString(1, eventId);
                        ps.setString(2, fishId);
                        try (var rs = ps.executeQuery()) {
                            return rs.next() ? rs.getInt(1) : 0;
                        }
                    }
                });
    }
}
