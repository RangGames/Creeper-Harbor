package wiki.creeper.harbor.core.inventory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** player_inventory · player_inventory_history (Core 소유, V3, 추가 제안). */
final class InventoryRepository {

    /**
     * 기록 한 줄.
     *
     * @param size 데이터 크기 (바이트)
     */
    record HistoryRow(long id, String reason, String server, Instant createdAt, int size) {}

    private InventoryRepository() {}

    static Optional<InventoryData> load(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT data FROM player_inventory WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decode(rs.getBytes(1))) : Optional.empty();
            }
        }
    }

    static void save(Connection c, UUID uuid, String server, byte[] encoded) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO player_inventory (uuid, format, data, server) VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE format = VALUES(format), data = VALUES(data),"
                                + " server = VALUES(server)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, InventoryData.FORMAT);
            ps.setBytes(3, encoded);
            ps.setString(4, server);
            ps.executeUpdate();
        }
    }

    /** 기록을 더하고 최근 keep개만 남긴다. 바로 앞 기록과 내용이 같으면 더하지 않는다 (사망 기록은 늘 더함). */
    static void addHistory(
            Connection c, UUID uuid, String reason, String server, byte[] encoded, int keep)
            throws SQLException {
        String hash = InventoryData.hash(encoded);
        if (!reason.equals("DEATH")) {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "SELECT hash FROM player_inventory_history WHERE uuid = ?"
                                    + " ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && hash.equals(rs.getString(1))) {
                        return;
                    }
                }
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO player_inventory_history (uuid, reason, server, format, data, hash)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, reason);
            ps.setString(3, server);
            ps.setInt(4, InventoryData.FORMAT);
            ps.setBytes(5, encoded);
            ps.setString(6, hash);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM player_inventory_history WHERE uuid = ? AND id < ("
                                + "SELECT id FROM (SELECT id FROM player_inventory_history WHERE uuid = ?"
                                + " ORDER BY id DESC LIMIT 1 OFFSET ?) t)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, Math.max(0, keep - 1));
            ps.executeUpdate();
        }
    }

    static List<HistoryRow> history(Connection c, UUID uuid, int limit) throws SQLException {
        List<HistoryRow> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT id, reason, server, created_at, LENGTH(data) FROM player_inventory_history"
                                + " WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(
                            new HistoryRow(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getTimestamp(4).toInstant(),
                                    rs.getInt(5)));
                }
            }
        }
        return rows;
    }

    static Optional<InventoryData> historyData(Connection c, UUID uuid, long id)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT data FROM player_inventory_history WHERE uuid = ? AND id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decode(rs.getBytes(1))) : Optional.empty();
            }
        }
    }

    private static InventoryData decode(byte[] data) throws SQLException {
        try {
            return InventoryData.decode(data);
        } catch (IOException e) {
            throw new SQLException("인벤토리 데이터를 읽지 못했습니다: " + e.getMessage(), e);
        }
    }
}
