package wiki.creeper.harbor.season.hof;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Json;
import wiki.creeper.harbor.season.api.HallOfFameEntry;
import wiki.creeper.harbor.season.api.HallOfFameService;
import wiki.creeper.harbor.season.api.HallOfFameSubmission;
import wiki.creeper.harbor.season.content.HallOfFameCategory;

/** hall_of_fame row reads and writes shared by live submissions and season finalization. */
final class HofRows {

    private static final Type DETAIL = new TypeToken<Map<String, String>>() {}.getType();
    private static final int MAX_RETRY = 3;

    record Applied(
            HallOfFameService.Outcome outcome,
            @Nullable HallOfFameEntry entry,
            @Nullable HallOfFameEntry previous) {}

    private HofRows() {}

    static Applied apply(
            Connection c,
            int season,
            HallOfFameCategory cat,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        String subject = subjectKey(sub.subject());
        for (int attempt = 0; ; attempt++) {
            try {
                return switch (cat.rule()) {
                    case TOP1 -> applyTop1(c, season, cat, subject, sub, finalRow);
                    case FIRST_N, ALL -> applyFirstN(c, season, cat, subject, sub, finalRow);
                };
            } catch (SQLIntegrityConstraintViolationException e) {
                if (attempt >= MAX_RETRY) {
                    throw e;
                }
            }
        }
    }

    private static Applied applyTop1(
            Connection c,
            int season,
            HallOfFameCategory cat,
            String subject,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        Optional<HallOfFameEntry> old = rowForUpdate(c, season, cat.category(), subject, 1);
        if (old.isPresent()) {
            HallOfFameEntry o = old.get();
            if (!HofMath.better(
                    cat.ascending(), sub.value(), sub.achievedAt(), o.value(), o.recordedAt())) {
                return new Applied(HallOfFameService.Outcome.NOT_RANKED, null, null);
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "UPDATE hall_of_fame SET uuid = ?, name_at_time = ?, value = ?, detail = ?, recorded_at = ?, `final` = ?"
                                    + " WHERE season = ? AND category = ? AND subject = ? AND `rank` = 1")) {
                ps.setString(1, sub.player().toString());
                ps.setString(2, clipName(sub.name()));
                ps.setDouble(3, sub.value());
                ps.setString(4, Json.GSON.toJson(sub.detail()));
                ps.setTimestamp(5, Timestamp.from(sub.achievedAt()));
                ps.setBoolean(6, finalRow);
                ps.setInt(7, season);
                ps.setString(8, cat.category());
                ps.setString(9, subject);
                ps.executeUpdate();
            }
            return new Applied(
                    HallOfFameService.Outcome.NEW_LEADER,
                    entry(season, cat, subject, 1, sub, finalRow),
                    o);
        }
        insert(c, season, cat.category(), subject, 1, sub, finalRow);
        return new Applied(
                HallOfFameService.Outcome.NEW_LEADER,
                entry(season, cat, subject, 1, sub, finalRow),
                null);
    }

    private static Applied applyFirstN(
            Connection c,
            int season,
            HallOfFameCategory cat,
            String subject,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        int count;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid FROM hall_of_fame WHERE season = ? AND category = ? AND subject = ? FOR UPDATE")) {
            ps.setInt(1, season);
            ps.setString(2, cat.category());
            ps.setString(3, subject);
            count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    if (sub.player().toString().equalsIgnoreCase(rs.getString(1))) {
                        return new Applied(HallOfFameService.Outcome.NOT_RANKED, null, null);
                    }
                }
            }
        }
        if (cat.count() > 0 && count >= cat.count()) {
            return new Applied(HallOfFameService.Outcome.NOT_RANKED, null, null);
        }
        int rank = count + 1;
        insert(c, season, cat.category(), subject, rank, sub, finalRow);
        return new Applied(
                rank == 1 ? HallOfFameService.Outcome.NEW_LEADER : HallOfFameService.Outcome.RANKED,
                entry(season, cat, subject, rank, sub, finalRow),
                null);
    }

    private static Optional<HallOfFameEntry> rowForUpdate(
            Connection c, int season, String category, String subject, int rank)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT season, category, subject, `rank`, uuid, name_at_time, value, detail, recorded_at, `final`"
                                + " FROM hall_of_fame WHERE season = ? AND category = ? AND subject = ? AND `rank` = ? FOR UPDATE")) {
            ps.setInt(1, season);
            ps.setString(2, category);
            ps.setString(3, subject);
            ps.setInt(4, rank);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static void insert(
            Connection c,
            int season,
            String category,
            String subject,
            int rank,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO hall_of_fame (season, category, subject, `rank`, uuid, name_at_time, value, detail, recorded_at, `final`)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, season);
            ps.setString(2, category);
            ps.setString(3, subject);
            ps.setInt(4, rank);
            ps.setString(5, sub.player().toString());
            ps.setString(6, clipName(sub.name()));
            ps.setDouble(7, sub.value());
            ps.setString(8, Json.GSON.toJson(sub.detail()));
            ps.setTimestamp(9, Timestamp.from(sub.achievedAt()));
            ps.setBoolean(10, finalRow);
            ps.executeUpdate();
        }
    }

    private static HallOfFameEntry entry(
            int season,
            HallOfFameCategory cat,
            String subject,
            int rank,
            HallOfFameSubmission sub,
            boolean finalRow) {
        return new HallOfFameEntry(
                season,
                cat.category(),
                subject,
                rank,
                sub.player(),
                sub.name(),
                sub.value(),
                sub.detail(),
                sub.achievedAt(),
                finalRow);
    }

    static HallOfFameEntry read(ResultSet rs) throws SQLException {
        Map<String, String> detail = Map.of();
        String raw = rs.getString(8);
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, String> parsed = Json.GSON.fromJson(raw, DETAIL);
                if (parsed != null) {
                    detail = parsed;
                }
            } catch (RuntimeException e) {
                detail = Map.of();
            }
        }
        return new HallOfFameEntry(
                rs.getInt(1),
                rs.getString(2),
                rs.getString(3),
                rs.getInt(4),
                UUID.fromString(rs.getString(5)),
                rs.getString(6),
                rs.getDouble(7),
                detail,
                rs.getTimestamp(9).toInstant(),
                rs.getBoolean(10));
    }

    private static String subjectKey(@Nullable HarborId subject) {
        return subject == null ? HallOfFameEntry.NO_SUBJECT : subject.toString();
    }

    private static String clipName(String name) {
        return name.length() > 64 ? name.substring(0, 64) : name;
    }
}
