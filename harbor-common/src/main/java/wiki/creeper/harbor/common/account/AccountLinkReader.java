package wiki.creeper.harbor.common.account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only lookup of linked accounts (docs 20 1.2, proposed). alt_link and ip_shared are
 * Proxy-owned tables that the Proxy's AltDetector fills on every login. Economy (same-IP trade
 * monitoring) and Fishing (trap alt accounts) read them through this class without depending on
 * Proxy.
 *
 * <p>This is I/O, so do not call it on the main thread (take a connection inside {@code
 * Database.async}). It makes no automatic ban decisions: IPs registered as shared (internet cafes,
 * schools) are only flagged via {@link LinkedAccount#sharedIp()}, and {@link #linkedIds} and {@link
 * #areLinked} exclude shared IPs.
 */
public final class AccountLinkReader {

    /**
     * One linked-account row.
     *
     * @param player the other account
     * @param ipHash HMAC-SHA256 of the shared IP (the raw IP is not stored)
     * @param hits number of logins observed together from the same IP
     * @param sharedIp whether the IP is a shared IP registered in ip_shared
     */
    public record LinkedAccount(
            UUID player,
            String ipHash,
            Instant firstSeen,
            Instant lastSeen,
            int hits,
            boolean sharedIp) {}

    private static final String LINKED =
            "SELECT CASE WHEN l.uuid_a = ? THEN l.uuid_b ELSE l.uuid_a END AS other,"
                    + " l.ip_hash, l.first_seen, l.last_seen, l.hits,"
                    + " (s.ip_hash IS NOT NULL) AS shared"
                    + " FROM alt_link l LEFT JOIN ip_shared s ON s.ip_hash = l.ip_hash"
                    + " WHERE l.uuid_a = ? OR l.uuid_b = ?"
                    + " ORDER BY l.last_seen DESC";

    private static final String PAIR =
            "SELECT 1 FROM alt_link l LEFT JOIN ip_shared s ON s.ip_hash = l.ip_hash"
                    + " WHERE l.uuid_a = ? AND l.uuid_b = ? AND s.ip_hash IS NULL LIMIT 1";

    private AccountLinkReader() {}

    /**
     * All linked accounts (including shared IPs, most recently observed first). An account linked
     * through several IPs has several rows.
     */
    public static List<LinkedAccount> linked(Connection connection, UUID player)
            throws SQLException {
        List<LinkedAccount> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(LINKED)) {
            String id = player.toString();
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setString(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(
                            new LinkedAccount(
                                    UUID.fromString(rs.getString("other")),
                                    rs.getString("ip_hash"),
                                    instant(rs.getTimestamp("first_seen")),
                                    instant(rs.getTimestamp("last_seen")),
                                    rs.getInt("hits"),
                                    rs.getBoolean("shared")));
                }
            }
        }
        return result;
    }

    /** UUIDs of linked accounts excluding shared IPs (for trade monitoring). */
    public static Set<UUID> linkedIds(Connection connection, UUID player) throws SQLException {
        Set<UUID> result = new LinkedHashSet<>();
        for (LinkedAccount account : linked(connection, player)) {
            if (!account.sharedIp()) {
                result.add(account.player());
            }
        }
        return result;
    }

    /** Whether two accounts are linked through a non-shared IP. */
    public static boolean areLinked(Connection connection, UUID a, UUID b) throws SQLException {
        if (a.equals(b)) {
            return false;
        }
        String[] pair = orderedPair(a, b);
        try (PreparedStatement ps = connection.prepareStatement(PAIR)) {
            ps.setString(1, pair[0]);
            ps.setString(2, pair[1]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Storage order in alt_link: the smaller UUID string (lowercase) is uuid_a. The writer (Proxy)
     * uses this function too.
     */
    public static String[] orderedPair(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? new String[] {x, y} : new String[] {y, x};
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
