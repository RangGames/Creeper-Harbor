package wiki.creeper.harbor.shop.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Offline shop notice SQL (shop_notice). All calls block, so call them only on virtual threads
 * (Database.async/tx).
 */
public final class NoticeRepository {

    private NoticeRepository() {}

    /** One unseen notice. */
    public record Notice(long id, String kind, long amount, @Nullable String detail) {}

    public static void insertNotice(
            Connection c, UUID player, String kind, long amount, @Nullable String detail)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO shop_notice (uuid, kind, amount, detail) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setLong(3, amount);
            ShopRepository.setNullable(ps, 4, detail);
            ps.executeUpdate();
        }
    }

    /** Reads unseen notices and marks them seen (one transaction). */
    public static List<Notice> takeNotices(Connection c, UUID player) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT id, kind, amount, detail FROM shop_notice WHERE uuid = ? AND seen_at IS NULL"
                                + " ORDER BY id LIMIT 20 FOR UPDATE")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notices.add(
                            new Notice(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getString(4)));
                }
            }
        }
        for (Notice n : notices) {
            try (PreparedStatement ps =
                    c.prepareStatement("UPDATE shop_notice SET seen_at = NOW(3) WHERE id = ?")) {
                ps.setLong(1, n.id());
                ps.executeUpdate();
            }
        }
        return notices;
    }
}
