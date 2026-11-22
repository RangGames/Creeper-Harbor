package wiki.creeper.harbor.npc.logic;

import java.util.List;
import java.util.Optional;
import wiki.creeper.harbor.npc.api.ReputationTier;

/** Reputation tier and cap calculations (no server API). */
public final class ReputationMath {

    private ReputationMath() {}

    /**
     * Tier for cumulative reputation. The tier table is ascending by tier and the first tier is at
     * 0 points. 1 if empty.
     */
    public static int tierFor(int value, List<ReputationTier> tiers) {
        int tier = tiers.isEmpty() ? 1 : tiers.getFirst().tier();
        for (ReputationTier t : tiers) {
            if (value >= t.points()) {
                tier = t.tier();
            }
        }
        return tier;
    }

    public static Optional<ReputationTier> next(int value, List<ReputationTier> tiers) {
        for (ReputationTier t : tiers) {
            if (t.points() > value) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * Actual increase after applying the daily cap. Negative amounts (admin deductions) ignore the
     * cap.
     *
     * @param usedToday amount already received today
     * @param cap no cap if 0 or less
     */
    public static int capped(int delta, int usedToday, int cap) {
        if (delta <= 0 || cap <= 0) {
            return delta;
        }
        return Math.max(0, Math.min(delta, cap - usedToday));
    }

    /**
     * Applies the modifier multiplier (CONTRIBUTION sources). Rounded; the multiplier is at least
     * 0.
     */
    public static int scaled(int delta, double multiplier) {
        if (delta <= 0) {
            return delta;
        }
        return (int) Math.round(delta * Math.max(0, multiplier));
    }

    /** Reputation never goes below 0. */
    public static int apply(int value, int delta) {
        long next = (long) value + delta;
        return (int) Math.clamp(next, 0L, Integer.MAX_VALUE);
    }
}
