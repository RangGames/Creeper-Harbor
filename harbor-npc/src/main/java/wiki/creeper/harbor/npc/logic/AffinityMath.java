package wiki.creeper.harbor.npc.logic;

import java.util.List;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.GiftReaction;

/** Affinity level and gift calculations (no server API). */
public final class AffinityMath {

    private AffinityMath() {}

    /** Level for cumulative affinity (levels ascending by points, level 0 at 0 points). */
    public static AffinityLevel levelFor(int points, List<AffinityLevel> levels) {
        AffinityLevel current = levels.getFirst();
        for (AffinityLevel l : levels) {
            if (points >= l.points()) {
                current = l;
            }
        }
        return current;
    }

    /**
     * Gift affinity. For fish, only positive reactions are multiplied by (1 + bonus × (★ − 1)),
     * rounded. Disliked gifts ignore quality.
     *
     * @param stars fish quality (0 for regular items)
     */
    public static int giftPoints(int base, int stars, double bonus) {
        if (base <= 0 || stars <= 1) {
            return base;
        }
        return (int) Math.round(base * (1 + bonus * (stars - 1)));
    }

    /** Picks the reaction from the table value (before the quality multiplier). */
    public static GiftReaction reaction(int base, int love, int like) {
        if (base >= love) {
            return GiftReaction.LOVE;
        }
        if (base >= like) {
            return GiftReaction.LIKE;
        }
        return base > 0 ? GiftReaction.NORMAL : GiftReaction.DISLIKE;
    }

    /** Affinity never goes below 0. */
    public static int apply(int points, int delta) {
        return (int) Math.clamp((long) points + delta, 0L, Integer.MAX_VALUE);
    }
}
