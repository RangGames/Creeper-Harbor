package wiki.creeper.harbor.season.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/** titles 쓰기 (서버 API 없음: 전환 엔진·harbor-tools에서도 쓴다). */
public final class TitleStore {

    public static final int MAX_REF = 64;

    private TitleStore() {}

    /**
     * 칭호를 넣는다. (uuid, title_id)가 이미 있으면 아무것도 하지 않는다 (멱등).
     *
     * @return 새로 넣었으면 true
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
