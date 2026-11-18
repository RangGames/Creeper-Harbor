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
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/** housing_display SQL (개인 어항 전시, 즉시 쓰기). */
public final class DisplayRepository {

    private DisplayRepository() {}

    public static List<DisplaySlot> load(Connection c, long plotId) throws SQLException {
        List<DisplaySlot> result = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT slot, fish_id, size, quality, first_caught, theme FROM housing_display"
                                + " WHERE plot_id = ? ORDER BY slot")) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt(1);
                    String rawFish = rs.getString(2);
                    double size = rs.getBigDecimal(3).doubleValue();
                    int quality = rs.getInt(4);
                    Timestamp first = rs.getTimestamp(5);
                    String rawTheme = rs.getString(6);
                    try {
                        result.add(
                                new DisplaySlot(
                                        slot,
                                        FishId.parse(rawFish),
                                        size,
                                        quality,
                                        first == null ? Instant.EPOCH : first.toInstant(),
                                        rawTheme == null
                                                ? null
                                                : HarborId.tryParse(rawTheme).orElse(null)));
                    } catch (IllegalArgumentException e) {
                        // 형식이 틀린 행 (콘텐츠에서 어종 ID가 바뀐 경우)은 건너뛴다
                    }
                }
            }
        }
        return result;
    }

    /** 칸에 전시 (테마는 그대로 둔다). */
    public static void upsert(Connection c, long plotId, UUID owner, DisplaySlot slot)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO housing_display (plot_id, slot, fish_id, size, quality, first_caught, owner)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE fish_id = VALUES(fish_id),"
                                + " size = VALUES(size), quality = VALUES(quality), first_caught = VALUES(first_caught),"
                                + " owner = VALUES(owner), updated_at = CURRENT_TIMESTAMP(3)")) {
            ps.setLong(1, plotId);
            ps.setInt(2, slot.slot());
            ps.setString(3, slot.fish().toString());
            ps.setBigDecimal(4, BigDecimal.valueOf(slot.size()).setScale(1, RoundingMode.HALF_UP));
            ps.setInt(5, slot.quality());
            ps.setTimestamp(6, Timestamp.from(slot.firstCaught()));
            ps.setString(7, owner.toString());
            ps.executeUpdate();
        }
    }

    public static boolean delete(Connection c, long plotId, int slot) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("DELETE FROM housing_display WHERE plot_id = ? AND slot = ?")) {
            ps.setLong(1, plotId);
            ps.setInt(2, slot);
            return ps.executeUpdate() == 1;
        }
    }

    public static boolean setTheme(Connection c, long plotId, int slot, @Nullable HarborId theme)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE housing_display SET theme = ?, updated_at = CURRENT_TIMESTAMP(3)"
                                + " WHERE plot_id = ? AND slot = ?")) {
            if (theme == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, theme.toString());
            }
            ps.setLong(2, plotId);
            ps.setInt(3, slot);
            return ps.executeUpdate() == 1;
        }
    }

    /** 테마 칸이 줄어든 칸들을 기본 테마로 (D14). */
    public static void revertThemes(Connection c, long plotId, List<Integer> slots)
            throws SQLException {
        for (int slot : slots) {
            setTheme(c, plotId, slot, null);
        }
    }
}
