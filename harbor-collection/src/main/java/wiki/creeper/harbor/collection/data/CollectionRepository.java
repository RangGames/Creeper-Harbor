package wiki.creeper.harbor.collection.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.SeasonId;

/** Collection SQL. 트랜잭션은 호출하는 쪽이 연다. */
public final class CollectionRepository {

    /** 기록 한 줄 (이름은 players 조인). */
    public record RecordRow(
            FishId fish, String league, UUID player, String name, double size, Instant caughtAt) {}

    /** 수족관 상태. */
    public record AquariumState(long points, int speciesCount, int stage) {
        public static final AquariumState EMPTY = new AquariumState(0, 0, 0);
    }

    /** 이번 시즌 기부된 어종 한 줄 (수조 연출·이름표). donor는 처음 기부한 사람 이름. */
    public record SpeciesRow(FishId fish, UUID firstDonor, String donor, Instant donatedAt) {}

    private CollectionRepository() {}

    // ------------------------------------------------------------------ 도감

    public static CodexData load(Connection c, UUID uuid, SeasonId season) throws SQLException {
        Map<FishId, CodexEntry> total =
                entries(
                        c,
                        "SELECT fish_id, first_caught, max_size, best_quality, catch_count"
                                + " FROM codex_total WHERE uuid = ?",
                        uuid,
                        null);
        Map<FishId, CodexEntry> seasonal =
                entries(
                        c,
                        "SELECT fish_id, first_caught, max_size, best_quality, catch_count"
                                + " FROM codex_season WHERE uuid = ? AND season = ?",
                        uuid,
                        season);
        Map<FishId, Integer> hints = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT fish_id, level FROM codex_hint WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int level = rs.getInt(2);
                    parse(rs.getString(1)).ifPresent(f -> hints.put(f, level));
                }
            }
        }
        Map<FishId, Integer> donations = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT fish_id, COUNT(*) FROM aquarium_donation WHERE season = ? AND uuid = ? GROUP BY fish_id")) {
            ps.setInt(1, season.value());
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int count = rs.getInt(2);
                    parse(rs.getString(1)).ifPresent(f -> donations.put(f, count));
                }
            }
        }
        return new CodexData(total, seasonal, hints, donations);
    }

    private static Map<FishId, CodexEntry> entries(
            Connection c, String sql, UUID uuid, @Nullable SeasonId season) throws SQLException {
        Map<FishId, CodexEntry> result = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            if (season != null) {
                ps.setInt(2, season.value());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CodexEntry entry =
                            new CodexEntry(
                                    rs.getTimestamp(2).toInstant(),
                                    rs.getBigDecimal(3).doubleValue(),
                                    rs.getInt(4),
                                    rs.getInt(5));
                    parse(rs.getString(1)).ifPresent(f -> result.put(f, entry));
                }
            }
        }
        return result;
    }

    /** 도감 업서트 (지연 쓰기). 플레이어 데이터는 세션 잠금을 가진 서버 하나만 쓰므로 포획 수는 메모리 값을 그대로 쓴다. */
    public static void upsertCodex(
            Connection c, UUID uuid, @Nullable SeasonId season, FishId fish, CodexEntry e)
            throws SQLException {
        String table =
                season == null
                        ? "codex_total (uuid, fish_id,"
                        : "codex_season (uuid, season, fish_id,";
        String values = season == null ? "?, ?," : "?, ?, ?,";
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO "
                                + table
                                + " first_caught, max_size, best_quality, catch_count) VALUES ("
                                + values
                                + " ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE first_caught = LEAST(first_caught, VALUES(first_caught)),"
                                + " max_size = GREATEST(max_size, VALUES(max_size)),"
                                + " best_quality = GREATEST(best_quality, VALUES(best_quality)), catch_count = VALUES(catch_count)")) {
            int i = 1;
            ps.setString(i++, uuid.toString());
            if (season != null) {
                ps.setInt(i++, season.value());
            }
            ps.setString(i++, fish.toString());
            ps.setTimestamp(i++, Timestamp.from(e.firstCaught()));
            ps.setBigDecimal(i++, size(e.maxSize()));
            ps.setInt(i++, e.bestQuality());
            ps.setInt(i, e.count());
            ps.executeUpdate();
        }
    }

    public static void deleteCodex(Connection c, UUID uuid, SeasonId season, FishId fish)
            throws SQLException {
        for (String sql :
                List.of(
                        "DELETE FROM codex_total WHERE uuid = ? AND fish_id = ?",
                        "DELETE FROM codex_season WHERE uuid = ? AND fish_id = ? AND season = ?")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, fish.toString());
                if (sql.contains("season = ?")) {
                    ps.setInt(3, season.value());
                }
                ps.executeUpdate();
            }
        }
    }

    public static void upsertHint(Connection c, UUID uuid, FishId fish, int level, String source)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO codex_hint (uuid, fish_id, level, source) VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE level = GREATEST(level, VALUES(level)), source = VALUES(source),"
                                + " updated_at = CURRENT_TIMESTAMP(3)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, fish.toString());
            ps.setInt(3, level);
            ps.setString(4, source);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 기록

    /** 개인 최고 업서트. 크기가 커질 때만 caught_at을 바꾼다 (UPDATE 순서 주의: caught_at을 먼저). */
    public static void upsertRecord(
            Connection c,
            FishId fish,
            SeasonId season,
            String league,
            UUID uuid,
            double size,
            Instant at)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO records (fish_id, season, league, uuid, size, caught_at) VALUES (?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE caught_at = IF(VALUES(size) > size, VALUES(caught_at), caught_at),"
                                + " size = GREATEST(size, VALUES(size))")) {
            ps.setString(1, fish.toString());
            ps.setInt(2, season.value());
            ps.setString(3, league);
            ps.setString(4, uuid.toString());
            ps.setBigDecimal(5, size(size));
            ps.setTimestamp(6, Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    public static void deleteRecord(
            Connection c, FishId fish, SeasonId season, String league, UUID uuid)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM records WHERE fish_id = ? AND season = ? AND league = ? AND uuid = ?")) {
            ps.setString(1, fish.toString());
            ps.setInt(2, season.value());
            ps.setString(3, league);
            ps.setString(4, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** 시즌 전체 (어종, 리그)별 상위 n명. */
    public static List<RecordRow> topRecords(Connection c, SeasonId season, int n)
            throws SQLException {
        List<RecordRow> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT t.fish_id, t.league, t.uuid, COALESCE(p.name, '?'), t.size, t.caught_at FROM ("
                                + " SELECT fish_id, league, uuid, size, caught_at,"
                                + " ROW_NUMBER() OVER (PARTITION BY fish_id, league ORDER BY size DESC, caught_at) AS rn"
                                + " FROM records WHERE season = ?) t LEFT JOIN players p ON p.uuid = t.uuid WHERE t.rn <= ?")) {
            ps.setInt(1, season.value());
            ps.setInt(2, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String league = rs.getString(2);
                    UUID player = UUID.fromString(rs.getString(3));
                    String name = rs.getString(4);
                    double sizeValue = rs.getBigDecimal(5).doubleValue();
                    Instant at = rs.getTimestamp(6).toInstant();
                    parse(rs.getString(1))
                            .ifPresent(
                                    f ->
                                            rows.add(
                                                    new RecordRow(
                                                            f, league, player, name, sizeValue,
                                                            at)));
                }
            }
        }
        return rows;
    }

    /** 그 리그에서 내 순위 (1부터). 기록이 없으면 0. */
    public static int rank(Connection c, FishId fish, SeasonId season, String league, UUID uuid)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 + (SELECT COUNT(*) FROM records r2 WHERE r2.fish_id = r.fish_id AND r2.season = r.season"
                                + " AND r2.league = r.league AND (r2.size > r.size OR (r2.size = r.size AND r2.caught_at < r.caught_at)))"
                                + " FROM records r WHERE r.fish_id = ? AND r.season = ? AND r.league = ? AND r.uuid = ?")) {
            ps.setString(1, fish.toString());
            ps.setInt(2, season.value());
            ps.setString(3, league);
            ps.setString(4, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ 수족관

    public static AquariumState aquarium(Connection c, SeasonId season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT points, species_count, stage FROM aquarium_state WHERE season = ?")) {
            ps.setInt(1, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new AquariumState(rs.getLong(1), rs.getInt(2), rs.getInt(3))
                        : AquariumState.EMPTY;
            }
        }
    }

    /**
     * 기부 한 마리 (트랜잭션 안). 서버에 처음 기부된 어종이면 true.
     *
     * @param itemUuid 고유 ID 아이템이면 그 ID (장부 상태는 호출한 쪽이 바꾼다)
     */
    public static boolean donate(
            Connection c,
            SeasonId season,
            UUID uuid,
            FishId fish,
            double size,
            int quality,
            @Nullable UUID itemUuid,
            int points)
            throws SQLException {
        boolean firstSpecies;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO aquarium_species (season, fish_id, first_donor) VALUES (?, ?, ?)")) {
            ps.setInt(1, season.value());
            ps.setString(2, fish.toString());
            ps.setString(3, uuid.toString());
            firstSpecies = ps.executeUpdate() == 1;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO aquarium_donation (season, uuid, fish_id, size, quality, item_uuid, points) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, season.value());
            ps.setString(2, uuid.toString());
            ps.setString(3, fish.toString());
            ps.setBigDecimal(4, size(size));
            ps.setInt(5, quality);
            if (itemUuid == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, itemUuid.toString());
            }
            ps.setInt(7, points);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO aquarium_state (season, points, species_count) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE points = points + VALUES(points),"
                                + " species_count = species_count + VALUES(species_count), updated_at = CURRENT_TIMESTAMP(3)")) {
            ps.setInt(1, season.value());
            ps.setInt(2, points);
            ps.setInt(3, firstSpecies ? 1 : 0);
            ps.executeUpdate();
        }
        return firstSpecies;
    }

    /** 단계는 시즌 안에서 내려가지 않는다. 실제로 올렸으면 true (여러 서버 중 한 번만). */
    public static boolean raiseStage(Connection c, SeasonId season, int stage) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE aquarium_state SET stage = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE season = ? AND stage < ?")) {
            ps.setInt(1, stage);
            ps.setInt(2, season.value());
            ps.setInt(3, stage);
            return ps.executeUpdate() == 1;
        }
    }

    /** 이번 시즌 기부된 어종 (먼저 기부된 순). */
    public static List<SpeciesRow> species(Connection c, SeasonId season) throws SQLException {
        List<SpeciesRow> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT s.fish_id, s.first_donor, COALESCE(p.name, '?'), s.donated_at FROM aquarium_species s"
                                + " LEFT JOIN players p ON p.uuid = s.first_donor WHERE s.season = ? ORDER BY s.donated_at")) {
            ps.setInt(1, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID donor = UUID.fromString(rs.getString(2));
                    String name = rs.getString(3);
                    Instant at = rs.getTimestamp(4).toInstant();
                    parse(rs.getString(1))
                            .ifPresent(f -> rows.add(new SpeciesRow(f, donor, name, at)));
                }
            }
        }
        return rows;
    }

    /** 방류 기록 (release_log, 추가 제안). */
    public static void logRelease(
            Connection c,
            SeasonId season,
            UUID uuid,
            FishId fish,
            double size,
            int quality,
            @Nullable UUID itemUuid)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO release_log (season, uuid, fish_id, size, quality, item_uuid) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, season.value());
            ps.setString(2, uuid.toString());
            ps.setString(3, fish.toString());
            ps.setBigDecimal(4, size(size));
            ps.setInt(5, quality);
            if (itemUuid == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, itemUuid.toString());
            }
            ps.executeUpdate();
        }
    }

    /** 기부 기록에서 다시 집계한다 (/harbor collection aquarium recount). */
    public static void recount(Connection c, SeasonId season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO aquarium_state (season, points, species_count)"
                                + " SELECT ?, COALESCE(SUM(points), 0), COUNT(DISTINCT fish_id) FROM aquarium_donation WHERE season = ?"
                                + " ON DUPLICATE KEY UPDATE points = VALUES(points), species_count = VALUES(species_count)")) {
            ps.setInt(1, season.value());
            ps.setInt(2, season.value());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 도우미

    private static BigDecimal size(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static Optional<FishId> parse(String raw) {
        try {
            return Optional.of(FishId.parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
