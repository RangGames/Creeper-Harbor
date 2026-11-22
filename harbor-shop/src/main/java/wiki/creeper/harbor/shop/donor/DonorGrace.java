package wiki.creeper.harbor.shop.donor;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * Donor subscription state calculation (docs 20 12.4 'donor tiers', tested without a server). Perks
 * last until period_end + grace (3 days). A cancel request (CANCEL_PENDING) also lasts until the
 * period ends, then becomes ENDED once grace passes. Perks remain during grace even if the renewal
 * payment is late.
 */
public final class DonorGrace {

    /** subscriptions.state. */
    public enum State implements Keyed {
        ACTIVE,
        CANCEL_PENDING,
        ENDED;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        public static State parse(@Nullable String raw) {
            if (raw == null) {
                return ENDED;
            }
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException e) {
                return ENDED;
            }
        }
    }

    private DonorGrace() {}

    /** Tier to apply now. */
    public static DonorTier effective(
            State state, DonorTier tier, @Nullable Instant periodEnd, Instant now, Duration grace) {
        if (state == State.ENDED || tier == DonorTier.NONE || periodEnd == null) {
            return DonorTier.NONE;
        }
        return now.isBefore(periodEnd.plus(grace)) ? tier : DonorTier.NONE;
    }

    /** Whether the cleanup task should mark it ENDED (past grace). */
    public static boolean shouldEnd(
            State state, @Nullable Instant periodEnd, Instant now, Duration grace) {
        if (state == State.ENDED) {
            return false;
        }
        return periodEnd == null || !now.isBefore(periodEnd.plus(grace));
    }

    /**
     * New period end after a renewal payment. Remaining time is appended after it (no loss for
     * renewing early).
     */
    public static Instant renewedEnd(@Nullable Instant periodEnd, Instant now, Duration period) {
        Instant base = periodEnd != null && periodEnd.isAfter(now) ? periodEnd : now;
        return base.plus(period);
    }
}
