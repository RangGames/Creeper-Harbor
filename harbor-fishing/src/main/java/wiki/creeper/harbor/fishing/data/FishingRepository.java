package wiki.creeper.harbor.fishing.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.fishing.food.ActiveFood;

/** pity_counter · fishing_food · fishing_flags SQL. */
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
        ActiveFood food = null;
        try (PreparedStatement ps =
                c.prepareStatement("SELECT item_id, expires_at FROM fishing_food WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        food =
                                new ActiveFood(
                                        ItemId.parse(rs.getString(1)),
                                        rs.getTimestamp(2).toInstant());
                    } catch (IllegalArgumentException e) {
                        // 형식이 틀린 행은 무시
                    }
                }
            }
        }
        return new FishingData(pity, food);
    }

    /** 요리 버프 저장 (먹을 때 즉시). null이면 지운다. */
    public static void saveFood(Connection c, UUID uuid, @Nullable ActiveFood food)
            throws SQLException {
        if (food == null) {
            try (PreparedStatement ps =
                    c.prepareStatement("DELETE FROM fishing_food WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO fishing_food (uuid, item_id, expires_at) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE item_id = VALUES(item_id),"
                                + " expires_at = VALUES(expires_at)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, food.item().toString());
            ps.setTimestamp(3, Timestamp.from(food.expiresAt()));
            ps.executeUpdate();
        }
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
