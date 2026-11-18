package wiki.creeper.harbor.world.housing;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.VisitPolicy;

/** housing_* 표 (가상 스레드). */
public final class HousingRepository {

    private HousingRepository() {}

    /** housing_plot 행 + 공동 건축자. */
    public record PlotRow(
            long plotId,
            UUID owner,
            String ownerName,
            int size,
            String theme,
            VisitPolicy policy,
            int likes,
            int gridX,
            int gridZ,
            double spawnX,
            double spawnY,
            double spawnZ,
            float spawnYaw,
            int redstone,
            Set<UUID> members) {

        public PlotRow {
            members = Set.copyOf(members);
        }
    }

    private static final String SELECT =
            "SELECT p.plot_id, p.owner, COALESCE(pl.name, ''), p.size, p.theme, p.visit_policy, p.likes, p.grid_x, p.grid_z, "
                    + "p.spawn_x, p.spawn_y, p.spawn_z, p.spawn_yaw, p.redstone FROM housing_plot p "
                    + "LEFT JOIN players pl ON pl.uuid = p.owner ";

    public static Optional<PlotRow> byOwner(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT + "WHERE p.owner = ?")) {
            ps.setObject(1, owner);
            return one(c, ps);
        }
    }

    public static Optional<PlotRow> byId(Connection c, long plotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT + "WHERE p.plot_id = ?")) {
            ps.setLong(1, plotId);
            return one(c, ps);
        }
    }

    private static Optional<PlotRow> one(Connection c, PreparedStatement ps) throws SQLException {
        PlotRow row = null;
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                VisitPolicy policy;
                try {
                    policy = VisitPolicy.valueOf(rs.getString(6));
                } catch (IllegalArgumentException e) {
                    policy = VisitPolicy.PUBLIC;
                }
                row =
                        new PlotRow(
                                rs.getLong(1),
                                rs.getObject(2, UUID.class),
                                rs.getString(3),
                                rs.getInt(4),
                                rs.getString(5),
                                policy,
                                rs.getInt(7),
                                rs.getInt(8),
                                rs.getInt(9),
                                rs.getDouble(10),
                                rs.getDouble(11),
                                rs.getDouble(12),
                                rs.getFloat(13),
                                rs.getInt(14),
                                Set.of());
            }
        }
        if (row == null) {
            return Optional.empty();
        }
        Set<UUID> members = new HashSet<>();
        try (PreparedStatement members2 =
                c.prepareStatement("SELECT uuid FROM housing_member WHERE plot_id = ?")) {
            members2.setLong(1, row.plotId());
            try (ResultSet rs = members2.executeQuery()) {
                while (rs.next()) {
                    members.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return Optional.of(
                new PlotRow(
                        row.plotId(),
                        row.owner(),
                        row.ownerName(),
                        row.size(),
                        row.theme(),
                        row.policy(),
                        row.likes(),
                        row.gridX(),
                        row.gridZ(),
                        row.spawnX(),
                        row.spawnY(),
                        row.spawnZ(),
                        row.spawnYaw(),
                        row.redstone(),
                        members));
    }

    /**
     * 새 플롯: 다음 나선 번호에 넣는다. 다른 서버와 겹치면(유니크 위반) 번호를 올려 다시 시도한다.
     *
     * @param spawn (블록 x, y, z, yaw)을 돌려주는 함수 (칸 번호 → 입장 지점)
     */
    public static long create(
            Connection c, UUID owner, int size, java.util.function.LongFunction<double[]> spawn)
            throws SQLException {
        long next;
        try (PreparedStatement ps =
                c.prepareStatement("SELECT COALESCE(MAX(plot_id) + 1, 0) FROM housing_plot")) {
            try (ResultSet rs = ps.executeQuery()) {
                next = rs.next() ? rs.getLong(1) : 0;
            }
        }
        for (int attempt = 0; attempt < 8; attempt++, next++) {
            PlotGrid.Cell cell = PlotGrid.cell(next);
            double[] s = spawn.apply(next);
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "INSERT IGNORE INTO housing_plot (plot_id, owner, size, grid_x, grid_z, spawn_x, spawn_y, spawn_z, spawn_yaw) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, next);
                ps.setObject(2, owner);
                ps.setInt(3, size);
                ps.setInt(4, cell.x());
                ps.setInt(5, cell.z());
                ps.setDouble(6, s[0]);
                ps.setDouble(7, s[1]);
                ps.setDouble(8, s[2]);
                ps.setFloat(9, (float) s[3]);
                if (ps.executeUpdate() > 0) {
                    return next;
                }
            }
            if (byOwner(c, owner).isPresent()) {
                return byOwner(c, owner).orElseThrow().plotId();
            }
        }
        throw new SQLException("빈 플롯 번호를 찾지 못했습니다");
    }

    public static void updateSize(Connection c, long plotId, int size) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE housing_plot SET size = ? WHERE plot_id = ? AND size < ?")) {
            ps.setInt(1, size);
            ps.setLong(2, plotId);
            ps.setInt(3, size);
            ps.executeUpdate();
        }
    }

    public static void updatePolicy(Connection c, long plotId, VisitPolicy policy)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE housing_plot SET visit_policy = ? WHERE plot_id = ?")) {
            ps.setString(1, policy.name());
            ps.setLong(2, plotId);
            ps.executeUpdate();
        }
    }

    public static void updateRedstone(Connection c, long plotId, int count) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE housing_plot SET redstone = ? WHERE plot_id = ?")) {
            ps.setInt(1, count);
            ps.setLong(2, plotId);
            ps.executeUpdate();
        }
    }

    public static void updateSpawn(
            Connection c, long plotId, double x, double y, double z, float yaw)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE housing_plot SET spawn_x = ?, spawn_y = ?, spawn_z = ?, spawn_yaw = ? WHERE plot_id = ?")) {
            ps.setDouble(1, x);
            ps.setDouble(2, y);
            ps.setDouble(3, z);
            ps.setFloat(4, yaw);
            ps.setLong(5, plotId);
            ps.executeUpdate();
        }
    }

    public static void transfer(Connection c, long plotId, UUID owner) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE housing_plot SET owner = ? WHERE plot_id = ?")) {
            ps.setObject(1, owner);
            ps.setLong(2, plotId);
            ps.executeUpdate();
        }
    }

    public static void member(Connection c, long plotId, UUID uuid, boolean add)
            throws SQLException {
        String sql =
                add
                        ? "INSERT IGNORE INTO housing_member (plot_id, uuid, role) VALUES (?, ?, 'BUILDER')"
                        : "DELETE FROM housing_member WHERE plot_id = ? AND uuid = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            ps.setObject(2, uuid);
            ps.executeUpdate();
        }
    }

    /** 좋아요: 하루 한 번만 (housing_like 기본 키). 새로 눌렀으면 플롯 likes를 올리고 새 값을 돌려준다, 이미 눌렀으면 −1. */
    public static int like(Connection c, long plotId, UUID uuid, LocalDate day)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO housing_like (plot_id, uuid, day) VALUES (?, ?, ?)")) {
            ps.setLong(1, plotId);
            ps.setObject(2, uuid);
            ps.setDate(3, Date.valueOf(day));
            if (ps.executeUpdate() == 0) {
                return -1;
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE housing_plot SET likes = likes + 1 WHERE plot_id = ?")) {
            ps.setLong(1, plotId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement("SELECT likes FROM housing_plot WHERE plot_id = ?")) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static Optional<UUID> uuidByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid FROM players WHERE name = ? ORDER BY last_join DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.ofNullable(rs.getObject(1, UUID.class))
                        : Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------------ 장식 보유 개수

    public static Map<HarborId, Integer> decor(Connection c, UUID uuid) throws SQLException {
        Map<HarborId, Integer> result = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT decor_id, count FROM housing_decor_owned WHERE uuid = ? AND count > 0")) {
            ps.setObject(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<HarborId> id = HarborId.tryParse(rs.getString(1));
                    int count = rs.getInt(2);
                    id.ifPresent(h -> result.put(h, count));
                }
            }
        }
        return result;
    }

    /** 개수 더하기 (구매·회수). */
    public static void addDecor(Connection c, UUID uuid, HarborId decor, int amount)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO housing_decor_owned (uuid, decor_id, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = count + VALUES(count)")) {
            ps.setObject(1, uuid);
            ps.setString(2, decor.toString());
            ps.setInt(3, amount);
            ps.executeUpdate();
        }
    }

    /** 개수 빼기 (팔레트에서 꺼냄). 모자라면 REJECTED. */
    public static void takeDecor(Connection c, UUID uuid, HarborId decor, int amount)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE housing_decor_owned SET count = count - ? WHERE uuid = ? AND decor_id = ? AND count >= ?")) {
            ps.setInt(1, amount);
            ps.setObject(2, uuid);
            ps.setString(3, decor.toString());
            ps.setInt(4, amount);
            if (ps.executeUpdate() != 1) {
                throw new LedgerException(LedgerError.REJECTED, "장식 개수가 모자랍니다");
            }
        }
    }

    // ------------------------------------------------------------------ 수조 자리

    public static List<DisplayAnchor> anchors(Connection c, long plotId) throws SQLException {
        List<DisplayAnchor> result = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT slot, decor_id, world, x, y, z, facing FROM housing_anchor WHERE plot_id = ? ORDER BY slot")) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<HarborId> decor = HarborId.tryParse(rs.getString(2));
                    if (decor.isPresent()) {
                        result.add(
                                new DisplayAnchor(
                                        plotId,
                                        rs.getInt(1),
                                        decor.get(),
                                        rs.getString(3),
                                        rs.getInt(4),
                                        rs.getInt(5),
                                        rs.getInt(6),
                                        rs.getString(7)));
                    }
                }
            }
        }
        return result;
    }

    public static void addAnchor(Connection c, DisplayAnchor a) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO housing_anchor (plot_id, slot, decor_id, world, x, y, z, facing) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, a.plotId());
            ps.setInt(2, a.slot());
            ps.setString(3, a.decor().toString());
            ps.setString(4, a.world());
            ps.setInt(5, a.x());
            ps.setInt(6, a.y());
            ps.setInt(7, a.z());
            ps.setString(8, a.facing());
            ps.executeUpdate();
        }
    }

    public static void removeAnchor(Connection c, long plotId, int slot) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("DELETE FROM housing_anchor WHERE plot_id = ? AND slot = ?")) {
            ps.setLong(1, plotId);
            ps.setInt(2, slot);
            ps.executeUpdate();
        }
    }

    /** 관리 명령 초기화: 공동 건축자·수조·좋아요를 지운다 (플롯 행과 장식 보유는 남긴다). */
    public static void reset(Connection c, long plotId) throws SQLException {
        for (String sql :
                List.of(
                        "DELETE FROM housing_member WHERE plot_id = ?",
                        "DELETE FROM housing_anchor WHERE plot_id = ?",
                        "DELETE FROM housing_like WHERE plot_id = ?",
                        "UPDATE housing_plot SET likes = 0, redstone = 0, visit_policy = 'PUBLIC' WHERE plot_id = ?")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, plotId);
                ps.executeUpdate();
            }
        }
    }
}
