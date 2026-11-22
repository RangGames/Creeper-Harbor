package wiki.creeper.harbor.world.sea;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * sea_event_log (virtual threads). Recovers in-progress events on restart; checks the minimum
 * interval and runs per season.
 */
public final class SeaEventRepository {

    private SeaEventRepository() {}

    /** One run record. */
    public record Row(
            String runId,
            String eventId,
            @Nullable String region,
            Instant warnAt,
            Instant startedAt,
            Instant endsAt,
            String state) {}

    public static boolean insert(
            Connection c,
            String runId,
            String eventId,
            @Nullable String region,
            int season,
            Instant warnAt,
            Instant startAt,
            Instant endAt,
            String state,
            boolean manual)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO sea_event_log (run_id, event_id, region, run_season, warn_at, started_at, ends_at, state, manual) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, runId);
            ps.setString(2, eventId);
            ps.setString(3, region);
            ps.setInt(4, season);
            ps.setTimestamp(5, Timestamp.from(warnAt));
            ps.setTimestamp(6, Timestamp.from(startAt));
            ps.setTimestamp(7, Timestamp.from(endAt));
            ps.setString(8, state);
            ps.setBoolean(9, manual);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * State transition. Changes only when in the from state and returns true if changed (only once
     * even if several servers do it at the same time).
     */
    public static boolean transition(
            Connection c, String runId, List<String> from, String to, @Nullable Instant endsAt)
            throws SQLException {
        String in = String.join(",", from.stream().map(s -> "'" + s + "'").toList());
        String sql =
                "UPDATE sea_event_log SET state = ?"
                        + (endsAt == null ? "" : ", ends_at = ?")
                        + " WHERE run_id = ? AND state IN ("
                        + in
                        + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, to);
            if (endsAt != null) {
                ps.setTimestamp(i++, Timestamp.from(endsAt));
            }
            ps.setString(i, runId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Announced and in-progress records. */
    public static List<Row> open(Connection c) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT run_id, event_id, region, warn_at, started_at, ends_at, state FROM sea_event_log "
                                + "WHERE state IN ('WARN', 'ACTIVE') ORDER BY started_at")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(
                            new Row(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getTimestamp(4).toInstant(),
                                    rs.getTimestamp(5).toInstant(),
                                    rs.getTimestamp(6).toInstant(),
                                    rs.getString(7)));
                }
            }
        }
        return rows;
    }

    /** Last start time per event (excluding cancelled). */
    public static Map<String, Instant> lastStarts(Connection c) throws SQLException {
        Map<String, Instant> result = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT event_id, MAX(started_at) FROM sea_event_log WHERE state <> 'CANCELLED' GROUP BY event_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp t = rs.getTimestamp(2);
                    if (t != null) {
                        result.put(rs.getString(1), t.toInstant());
                    }
                }
            }
        }
        return result;
    }

    /** Number of runs per event this season (excluding cancelled). */
    public static Map<String, Integer> seasonCounts(Connection c, int season) throws SQLException {
        Map<String, Integer> result = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT event_id, COUNT(*) FROM sea_event_log WHERE run_season = ? AND state <> 'CANCELLED' GROUP BY event_id")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return result;
    }
}
