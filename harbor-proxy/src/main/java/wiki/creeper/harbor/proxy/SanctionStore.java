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

/** SQL for the sanctions table (Proxy-owned). Connections use the caller's transaction. */
final class SanctionStore {

    private static final String COLUMNS =
            "id, uuid, type, reason, issued_by, issued_at, expires_at";

    private SanctionStore() {}

    /** Currently active sanctions (not lifted and not expired). */
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

    /**
     * Lifts the existing sanction of the same kind and inserts the new one (one active sanction per
     * kind per person).
     */
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

    /** Lifts active sanctions. Returns the number lifted. */
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
