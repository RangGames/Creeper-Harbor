package wiki.creeper.harbor.common.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * sessions table: record of the last owning server (for audit/recovery). The actual lock lives in
 * Redis.
 */
public final class SessionAudit {

    private SessionAudit() {}

    public static void recordOwner(
            Connection connection, UUID player, String server, Instant lockedUntil)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "INSERT INTO sessions (uuid, server, locked_until) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE server = VALUES(server), locked_until = VALUES(locked_until)")) {
            ps.setString(1, player.toString());
            ps.setString(2, server);
            ps.setTimestamp(3, Timestamp.from(lockedUntil));
            ps.executeUpdate();
        }
    }
}
