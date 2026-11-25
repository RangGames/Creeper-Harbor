package wiki.creeper.harbor.common.account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Best reputation tier reached across seasons (players.best_rep_tier, '11. Data design' 3.2, D11).
 * players is a Common-owned table; NPC raises the value when a tier goes up, and housing size and
 * display slots are judged by it. Kept across season transitions.
 *
 * <p>Blocking: call inside {@code Database.async}/{@code tx}, never on the main thread.
 */
public final class BestReputation {

    private BestReputation() {}

    /** Stored tier, or empty when the player has no players row yet. */
    public static OptionalInt read(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT best_rep_tier FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            }
        }
    }

    /**
     * Raises the stored tier to {@code tier} if higher (it never goes down). Does nothing when the
     * player has no players row.
     */
    public static void raise(Connection c, UUID player, int tier) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE players SET best_rep_tier = GREATEST(best_rep_tier, ?) WHERE uuid = ?")) {
            ps.setInt(1, tier);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }
}
