package wiki.creeper.harbor.shop.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.shop.donor.DonorGrace;

/**
 * Donor subscription SQL (subscriptions). All calls block, so call them only on virtual threads
 * (Database.async/tx).
 */
public final class SubscriptionRepository {

    private SubscriptionRepository() {}

    /** One subscriptions row. */
    public record Subscription(
            UUID player,
            DonorTier tier,
            @Nullable String recurringId,
            DonorGrace.State state,
            @Nullable Instant periodEnd) {}

    public static Optional<Subscription> subscription(Connection c, UUID player)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, tier, tebex_recurring_id, state, period_end FROM subscriptions WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(subscriptionRow(rs)) : Optional.empty();
            }
        }
    }

    public static Optional<Subscription> subscriptionByRecurring(Connection c, String recurringId)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, tier, tebex_recurring_id, state, period_end FROM subscriptions"
                                + " WHERE tebex_recurring_id = ? FOR UPDATE")) {
            ps.setString(1, recurringId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(subscriptionRow(rs)) : Optional.empty();
            }
        }
    }

    /** All subscriptions that have not ended (startup reconciliation and grace cleanup). */
    public static List<Subscription> openSubscriptions(Connection c) throws SQLException {
        List<Subscription> rows = new ArrayList<>();
        try (PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT uuid, tier, tebex_recurring_id, state, period_end FROM subscriptions"
                                        + " WHERE state <> 'ENDED'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(subscriptionRow(rs));
            }
        }
        return rows;
    }

    public static void upsertSubscription(Connection c, Subscription s) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO subscriptions (uuid, tier, tebex_recurring_id, state, period_end)"
                                + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE tier = VALUES(tier),"
                                + " tebex_recurring_id = COALESCE(VALUES(tebex_recurring_id), tebex_recurring_id),"
                                + " state = VALUES(state), period_end = VALUES(period_end)")) {
            ps.setString(1, s.player().toString());
            ps.setString(2, s.tier().name());
            ShopRepository.setNullable(ps, 3, s.recurringId());
            ps.setString(4, s.state().name());
            if (s.periodEnd() == null) {
                ps.setNull(5, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(5, Timestamp.from(s.periodEnd()));
            }
            ps.executeUpdate();
        }
    }

    /**
     * Moves a subscription past its grace period to ENDED (conditional on the expected state).
     * Returns true if changed.
     */
    public static boolean endSubscription(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE subscriptions SET state = 'ENDED' WHERE uuid = ? AND state <> 'ENDED'")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() == 1;
        }
    }

    private static Subscription subscriptionRow(ResultSet rs) throws SQLException {
        Timestamp end = rs.getTimestamp(5);
        return new Subscription(
                UUID.fromString(rs.getString(1)),
                DonorTier.parse(rs.getString(2)),
                rs.getString(3),
                DonorGrace.State.parse(rs.getString(4)),
                end == null ? null : end.toInstant());
    }
}
