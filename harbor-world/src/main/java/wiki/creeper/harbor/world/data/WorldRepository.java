package wiki.creeper.harbor.world.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.SeasonId;

/** Reads and writes region_visit · sea_chart · world_player_state (virtual threads). */
public final class WorldRepository {

    private WorldRepository() {}

    public static WorldState load(Connection c, UUID uuid, SeasonId season) throws SQLException {
        Set<RegionId> visited = new HashSet<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT region_id FROM region_visit WHERE uuid = ? AND season = ?")) {
            ps.setObject(1, uuid);
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parse(rs.getString(1)).ifPresent(visited::add);
                }
            }
        }
        Map<RegionId, WorldState.ChartRow> charts = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT region_id, bits, visited, claimed FROM sea_chart WHERE uuid = ? AND season = ?")) {
            ps.setObject(1, uuid);
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RegionId region = parse(rs.getString(1)).orElse(null);
                    byte[] bits = rs.getBytes(2);
                    if (region != null && bits != null) {
                        charts.put(
                                region, new WorldState.ChartRow(bits, rs.getInt(3), rs.getInt(4)));
                    }
                }
            }
        }
        double cold = 0;
        double oxygen = -1;
        WorldState.SafePos safe = null;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT cold, oxygen, safe_world, safe_x, safe_y, safe_z FROM world_player_state WHERE uuid = ?")) {
            ps.setObject(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cold = rs.getDouble(1);
                    oxygen = rs.getDouble(2);
                    String world = rs.getString(3);
                    if (world != null) {
                        safe =
                                new WorldState.SafePos(
                                        world, rs.getDouble(4), rs.getDouble(5), rs.getDouble(6));
                    }
                }
            }
        }
        return new WorldState(visited, charts, cold, oxygen, safe);
    }

    public static void save(Connection c, UUID uuid, SeasonId season, WorldState state)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO region_visit (uuid, season, region_id) VALUES (?, ?, ?)")) {
            for (RegionId region : state.visited()) {
                ps.setObject(1, uuid);
                ps.setInt(2, season.value());
                ps.setString(3, region.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO sea_chart (uuid, season, region_id, bits, visited, claimed) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE bits = VALUES(bits), visited = VALUES(visited), "
                                + "claimed = claimed | VALUES(claimed)")) {
            for (Map.Entry<RegionId, WorldState.ChartRow> e : state.charts().entrySet()) {
                ps.setObject(1, uuid);
                ps.setInt(2, season.value());
                ps.setString(3, e.getKey().toString());
                ps.setBytes(4, e.getValue().bits());
                ps.setInt(5, e.getValue().visited());
                ps.setInt(6, e.getValue().claimed());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        WorldState.SafePos safe = state.safe();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO world_player_state (uuid, cold, oxygen, safe_world, safe_x, safe_y, safe_z) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE cold = VALUES(cold), "
                                + "oxygen = VALUES(oxygen), safe_world = VALUES(safe_world), safe_x = VALUES(safe_x), "
                                + "safe_y = VALUES(safe_y), safe_z = VALUES(safe_z)")) {
            ps.setObject(1, uuid);
            ps.setDouble(2, state.cold());
            ps.setDouble(3, state.oxygen());
            ps.setString(4, safe == null ? null : safe.world());
            ps.setDouble(5, safe == null ? 0 : safe.x());
            ps.setDouble(6, safe == null ? 0 : safe.y());
            ps.setDouble(7, safe == null ? 0 : safe.z());
            ps.executeUpdate();
        }
    }

    /**
     * Records a first visit immediately (write-behind queue key player:&lt;uuid&gt;:world:visit:…).
     */
    public static void insertVisit(Connection c, UUID uuid, int season, RegionId region)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO region_visit (uuid, season, region_id) VALUES (?, ?, ?)")) {
            ps.setObject(1, uuid);
            ps.setInt(2, season);
            ps.setString(3, region.toString());
            ps.executeUpdate();
        }
    }

    public static boolean visited(Connection c, UUID uuid, int season, RegionId region)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM region_visit WHERE uuid = ? AND season = ? AND region_id = ?")) {
            ps.setObject(1, uuid);
            ps.setInt(2, season);
            ps.setString(3, region.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static java.util.Optional<RegionId> parse(String raw) {
        try {
            return java.util.Optional.of(RegionId.parse(raw));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
