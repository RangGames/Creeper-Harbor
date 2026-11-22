package wiki.creeper.harbor.season.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/** titles writes (no server API: also used by the transition engine and harbor-tools). */
public final class TitleStore {

    public static final int MAX_REF = 64;

    private TitleStore() {}

    /**
     * Inserts a title. Does nothing if (uuid, title_id) already exists (idempotent).
     *
     * @return true if newly inserted
     */
    public static boolean insert(
            Connection c, UUID player, TitleId title, int season, @Nullable String refId)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO titles (uuid, title_id, season, ref_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, title.toString());
            ps.setInt(3, season);
            ps.setString(4, clip(refId));
            return ps.executeUpdate() == 1;
        }
    }

    public static @Nullable String clip(@Nullable String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        return ref.length() > MAX_REF ? ref.substring(0, MAX_REF) : ref;
    }
}
