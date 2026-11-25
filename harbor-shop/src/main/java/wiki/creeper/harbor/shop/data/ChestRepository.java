package wiki.creeper.harbor.shop.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Pearl chest SQL (chest_pity, chest_open_log, shop_shards). All calls block, so call them only on
 * virtual threads (Database.async/tx).
 */
public final class ChestRepository {

    private ChestRepository() {}

    public static int lockPity(Connection c, UUID player, int season, HarborId chest)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO chest_pity (uuid, season, chest_id) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE season = season")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, chest.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT count FROM chest_pity WHERE uuid = ? AND season = ? AND chest_id = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, chest.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static int pity(Connection c, UUID player, int season, HarborId chest)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT count FROM chest_pity WHERE uuid = ? AND season = ? AND chest_id = ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, chest.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static void setPity(Connection c, UUID player, int season, HarborId chest, int count)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE chest_pity SET count = ? WHERE uuid = ? AND season = ? AND chest_id = ?")) {
            ps.setInt(1, count);
            ps.setString(2, player.toString());
            ps.setInt(3, season);
            ps.setString(4, chest.toString());
            ps.executeUpdate();
        }
    }

    /** One chest-open log row. */
    public record OpenLog(
            UUID player,
            HarborId chest,
            int tableVersion,
            int roll,
            HarborId result,
            String grade,
            boolean pityHit,
            boolean duplicate,
            int pityBefore,
            UUID orderId) {}

    public static void logOpen(Connection c, OpenLog log) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO chest_open_log (uuid, chest_id, table_version, roll, result, grade,"
                                + " pity_hit, duplicate, pity_before, order_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, log.player().toString());
            ps.setString(2, log.chest().toString());
            ps.setInt(3, log.tableVersion());
            ps.setInt(4, log.roll());
            ps.setString(5, log.result().toString());
            ps.setString(6, log.grade());
            ps.setBoolean(7, log.pityHit());
            ps.setBoolean(8, log.duplicate());
            ps.setInt(9, log.pityBefore());
            ps.setString(10, log.orderId().toString());
            ps.executeUpdate();
        }
    }

    public static long shards(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT shards FROM shop_shards WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * Adds or subtracts pearl shards. Returns false (no change) if the result would be negative.
     */
    public static boolean addShards(Connection c, UUID player, long delta) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO shop_shards (uuid) VALUES (?) ON DUPLICATE KEY UPDATE uuid = uuid")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE shop_shards SET shards = shards + ? WHERE uuid = ? AND shards + ? >= 0")) {
            ps.setLong(1, delta);
            ps.setString(2, player.toString());
            ps.setLong(3, delta);
            return ps.executeUpdate() == 1;
        }
    }
}
