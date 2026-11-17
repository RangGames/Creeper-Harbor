package wiki.creeper.harbor.fishing.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.SeasonId;

/** pity_counter · fishing_flags SQL. */
public final class FishingRepository {

    private FishingRepository() {}

    public static FishingData load(Connection c, UUID uuid, SeasonId season) throws SQLException {
        Map<FishId, Integer> pity = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT fish_id, casts FROM pity_counter WHERE uuid = ? AND season = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        pity.put(FishId.parse(rs.getString(1)), rs.getInt(2));
                    } catch (IllegalArgumentException e) {
                        // 형식이 틀린 행은 무시
                    }
                }
            }
        }
        return new FishingData(pity);
    }

    /** 천장 카운터 전체 저장 (지연 쓰기). 어종 수가 적어 전부 쓴다. */
    public static void save(Connection c, UUID uuid, SeasonId season, FishingData data)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO pity_counter (uuid, season, fish_id, casts) VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE casts = VALUES(casts)")) {
            for (Map.Entry<FishId, Integer> e : data.pity().entrySet()) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, season.value());
                ps.setString(3, e.getKey().toString());
                ps.setInt(4, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 전설 포획: 그 어종의 카운터를 즉시 0으로. */
    public static void resetPity(Connection c, UUID uuid, SeasonId season, FishId fish)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO pity_counter (uuid, season, fish_id, casts) VALUES (?, ?, ?, 0)"
                                + " ON DUPLICATE KEY UPDATE casts = 0")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setString(3, fish.toString());
            ps.executeUpdate();
        }
    }

    public static void insertFlag(Connection c, UUID uuid, int score, String reasons, String action)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO fishing_flags (uuid, score, reasons, action) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, score);
            ps.setString(3, reasons);
            ps.setString(4, action);
            ps.executeUpdate();
        }
    }
}
