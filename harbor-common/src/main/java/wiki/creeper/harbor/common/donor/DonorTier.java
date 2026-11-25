package wiki.creeper.harbor.common.donor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * Donor tier ('6. BM design' 3.2, docs 20 0.1). Subscription definitions (subscriptions) belong to
 * Shop; lookups happen here. The Proxy queue and the World/Collection aquarium theme slots use it
 * without depending on Shop. All perks are non-performance.
 *
 * <p>Shop updates the Redis hash {@code harbor:donor} (field = UUID, value = tier name) whenever a
 * subscription changes. The 3-day grace period is applied when Shop removes the value. When the
 * Redis value is missing (or Redis is down), the lookup falls back to the Shop-owned {@code
 * subscriptions} table ('11. Data design' 4.1 harbor:donor, docs 19 5.12): state and period_end
 * plus the same grace period. Without a registered database (or without the table, i.e. no Shop)
 * the result is NONE.
 */
public enum DonorTier {
    NONE(0, 0),
    /** Harbor supporter: name color/title, chat emoji, queue priority, 300 pearls per month. */
    HARBOR(1, 0),
    /**
     * Fleet supporter: the perks above + flag/sail emblems, +2 aquarium theme slots (cosmetic
     * slots), 700 pearls per month.
     */
    FLEET(2, 2);

    public static final String REDIS_KEY = "harbor:donor";

    /** Perks last until period_end + this grace (Shop shop.yml grace_days default). */
    public static final Duration GRACE = Duration.ofDays(3);

    /** Database for the subscriptions fallback (registered by Core and Proxy at startup). */
    private static volatile @Nullable Database fallback;

    private final int rank;
    private final int extraThemeSlots;

    DonorTier(int rank, int extraThemeSlots) {
        this.rank = rank;
        this.extraThemeSlots = extraThemeSlots;
    }

    public int rank() {
        return rank;
    }

    /** Extra aquarium decoration theme slots (separate from display slot count, D14). */
    public int extraThemeSlots() {
        return extraThemeSlots;
    }

    public boolean atLeast(DonorTier other) {
        return rank >= other.rank;
    }

    /** Whether this tier gets queue priority. */
    public boolean queuePriority() {
        return rank >= HARBOR.rank;
    }

    public static DonorTier parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /**
     * Registers the database used when Redis has no value (Core and Proxy call this after the DB
     * connects; null on shutdown). Callers of {@link #lookup(Redis, UUID)} then get the DB fallback
     * without passing the database themselves.
     */
    public static void useDatabase(@Nullable Database db) {
        fallback = db;
    }

    /**
     * Reads from Redis, then from subscriptions if Redis has no value or fails (I/O, so do not call
     * on the main thread). Uses the database registered with {@link #useDatabase}. NONE on failure.
     */
    public static DonorTier lookup(Redis redis, UUID player) {
        return lookup(redis, fallback, player);
    }

    /**
     * Reads from Redis, then from subscriptions through {@code db} if Redis has no value or fails
     * (I/O, not on the main thread). NONE on failure or when {@code db} is null.
     */
    public static DonorTier lookup(Redis redis, @Nullable Database db, UUID player) {
        String raw;
        try {
            raw = redis.sync().hget(REDIS_KEY, player.toString());
        } catch (RuntimeException e) {
            raw = null;
        }
        if (raw != null && !raw.isBlank()) {
            return parse(raw);
        }
        return db == null ? NONE : fromDatabase(db, player, Instant.now());
    }

    /** Tier from subscriptions only (blocking, virtual thread). NONE on failure. */
    public static DonorTier fromDatabase(Database db, UUID player, Instant now) {
        try {
            return db.tx(c -> fromSubscriptions(c, player, now));
        } catch (SQLException | RuntimeException e) {
            return NONE;
        }
    }

    /**
     * Reads one subscriptions row (Shop-owned table, read only here). NONE without a row or when
     * the table does not exist.
     */
    public static DonorTier fromSubscriptions(Connection c, UUID player, Instant now)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT tier, state, period_end FROM subscriptions WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return NONE;
                }
                Timestamp end = rs.getTimestamp(3);
                return effective(
                        parse(rs.getString(1)),
                        rs.getString(2),
                        end == null ? null : end.toInstant(),
                        now);
            }
        } catch (SQLException e) {
            // 42S02 = table missing (Shop not installed on this network)
            if ("42S02".equals(e.getSQLState())) {
                return NONE;
            }
            throw e;
        }
    }

    /**
     * Tier in effect for a subscriptions row: ENDED or no period end means NONE, otherwise the tier
     * until period_end + {@link #GRACE} (same rule as Shop DonorGrace).
     */
    public static DonorTier effective(
            DonorTier tier, @Nullable String state, @Nullable Instant periodEnd, Instant now) {
        if (tier == NONE || state == null || state.equals("ENDED") || periodEnd == null) {
            return NONE;
        }
        return now.isBefore(periodEnd.plus(GRACE)) ? tier : NONE;
    }
}
