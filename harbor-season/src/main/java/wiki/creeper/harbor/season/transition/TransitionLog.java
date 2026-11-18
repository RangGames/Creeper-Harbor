package wiki.creeper.harbor.season.transition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;

/** season_transition_log (체크포인트 일지, 추가 제안). 단계마다 시작·완료를 남긴다. */
public final class TransitionLog {

    private final Database db;

    public TransitionLog(Database db) {
        this.db = db;
    }

    public void begin(String id, int from, int to, String step, String operator)
            throws SQLException {
        db.txVoid(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO season_transition_log (transition_id, from_season, to_season, step, state, operator)"
                                            + " VALUES (?, ?, ?, ?, 'STARTED', ?)"
                                            + " ON DUPLICATE KEY UPDATE state = IF(state IN ('DONE', 'SKIPPED'), state, 'STARTED'),"
                                            + " started_at = IF(state IN ('DONE', 'SKIPPED'), started_at, CURRENT_TIMESTAMP(3)), operator = VALUES(operator)")) {
                        ps.setString(1, id);
                        ps.setInt(2, from);
                        ps.setInt(3, to);
                        ps.setString(4, step);
                        ps.setString(5, operator);
                        ps.executeUpdate();
                    }
                });
    }

    public void finish(
            String id, String step, String state, long rows, long checksum, @Nullable String detail)
            throws SQLException {
        db.txVoid(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE season_transition_log SET state = ?, `rows` = ?, checksum = ?, detail = ?, finished_at = CURRENT_TIMESTAMP(3)"
                                            + " WHERE transition_id = ? AND step = ?")) {
                        ps.setString(1, state);
                        ps.setLong(2, rows);
                        ps.setLong(3, checksum);
                        ps.setString(4, detail == null ? null : clip(detail));
                        ps.setString(5, id);
                        ps.setString(6, step);
                        ps.executeUpdate();
                    }
                });
    }

    public List<LogRow> rows(String id) throws SQLException {
        return db.tx(
                c -> {
                    List<LogRow> result = new ArrayList<>();
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT transition_id, from_season, to_season, step, state, `rows`, checksum, detail, started_at, finished_at, operator"
                                            + " FROM season_transition_log WHERE transition_id = ? ORDER BY id")) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                Timestamp finished = rs.getTimestamp(10);
                                result.add(
                                        new LogRow(
                                                rs.getString(1),
                                                rs.getInt(2),
                                                rs.getInt(3),
                                                rs.getString(4),
                                                rs.getString(5),
                                                rs.getLong(6),
                                                rs.getLong(7),
                                                rs.getString(8),
                                                rs.getTimestamp(9).toInstant(),
                                                finished == null ? null : finished.toInstant(),
                                                rs.getString(11)));
                            }
                        }
                    }
                    return result;
                });
    }

    /** 끝나는 시즌의 가장 최근 전환 ID. */
    public Optional<String> latest(int fromSeason) throws SQLException {
        return db.tx(c -> latest(c, fromSeason));
    }

    static Optional<String> latest(Connection c, int fromSeason) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT transition_id FROM season_transition_log WHERE from_season = ? ORDER BY id DESC LIMIT 1")) {
            ps.setInt(1, fromSeason);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static String clip(String s) {
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
