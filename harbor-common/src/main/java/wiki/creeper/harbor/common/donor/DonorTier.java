package wiki.creeper.harbor.common.donor;

import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * Donor tier ('6. BM design' 3.2, docs 20 0.1). Subscription definitions (subscriptions) belong to
 * Shop; lookups happen here. The Proxy queue and the World/Collection aquarium theme slots use it
 * without depending on Shop. All perks are non-performance.
 *
 * <p>Shop updates the Redis hash {@code harbor:donor} (field = UUID, value = tier name) whenever a
 * subscription changes. The 3-day grace period is applied when Shop removes the value. A missing
 * Redis value means NONE (Shop refills it from the DB at startup).
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

    /** Reads from Redis (I/O, so do not call on the main thread). NONE on failure. */
    public static DonorTier lookup(Redis redis, UUID player) {
        try {
            return parse(redis.sync().hget(REDIS_KEY, player.toString()));
        } catch (RuntimeException e) {
            return NONE;
        }
    }
}
