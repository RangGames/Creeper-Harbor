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
                        // Ignore malformed rows
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
                        // Ignore malformed rows
                    }
                }
            }
        }
        return new FishingData(pity, food);
    }

    /** Saves the food buff (immediately on eating). Deletes it when null. */
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

    /** Saves all pity counters (write-behind). There are few species, so all are written. */
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

    /** Legendary catch: resets that species' counter to 0 immediately. */
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
