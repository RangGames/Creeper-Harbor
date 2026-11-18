package wiki.creeper.harbor.proxy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** sanctions 테이블 SQL (Proxy 소유). 연결은 호출한 쪽의 트랜잭션을 쓴다. */
final class SanctionStore {

    private static final String COLUMNS =
            "id, uuid, type, reason, issued_by, issued_at, expires_at";

    private SanctionStore() {}

    /** 지금 유효한 제재 (해제되지 않았고 만료 전). */
    static List<Sanction> active(Connection c, UUID player, Instant now) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT "
                                + COLUMNS
                                + " FROM sanctions WHERE uuid = ? AND revoked = 0"
                                + " AND (expires_at IS NULL OR expires_at > ?) ORDER BY issued_at DESC")) {
            ps.setString(1, player.toString());
            ps.setTimestamp(2, Timestamp.from(now));
            return read(ps);
        }
    }

    /** 같은 종류의 기존 제재를 해제하고 새 제재를 넣는다 (한 사람당 종류별 유효 제재는 하나). */
    static Sanction issue(
            Connection c,
            UUID player,
            Sanction.Type type,
            String reason,
            String issuedBy,
            Instant now,
            @Nullable Instant expiresAt)
            throws SQLException {
        var _ = revoke(c, player, type, issuedBy, now);
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO sanctions (uuid, type, reason, issued_by, issued_at, expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.toString());
            ps.setString(2, type.name());
            ps.setString(3, reason.length() > 255 ? reason.substring(0, 255) : reason);
            ps.setString(4, issuedBy);
            ps.setTimestamp(5, Timestamp.from(now));
            if (expiresAt == null) {
                ps.setNull(6, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(6, Timestamp.from(expiresAt));
            }
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getLong(1) : -1L;
            }
            return new Sanction(id, player, type, reason, issuedBy, now, expiresAt);
        }
    }

    /** 유효한 제재를 해제한다. 해제한 건수. */
    static int revoke(Connection c, UUID player, Sanction.Type type, String by, Instant now)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE sanctions SET revoked = 1, revoked_by = ?, revoked_at = ?"
                                + " WHERE uuid = ? AND type = ? AND revoked = 0"
                                + " AND (expires_at IS NULL OR expires_at > ?)")) {
            ps.setString(1, by);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, player.toString());
            ps.setString(4, type.name());
            ps.setTimestamp(5, Timestamp.from(now));
            return ps.executeUpdate();
        }
    }

    private static List<Sanction> read(PreparedStatement ps) throws SQLException {
        List<Sanction> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Sanction.Type type;
                try {
                    type = Sanction.Type.valueOf(rs.getString("type"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                @Nullable Timestamp expires = rs.getTimestamp("expires_at");
                result.add(
                        new Sanction(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("uuid")),
                                type,
                                rs.getString("reason"),
                                rs.getString("issued_by"),
                                rs.getTimestamp("issued_at").toInstant(),
                                expires == null ? null : expires.toInstant()));
            }
        }
        return result;
    }
}
