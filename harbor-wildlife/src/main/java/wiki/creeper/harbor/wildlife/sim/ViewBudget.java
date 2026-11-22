package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Display selection ('17-3' 9.4 distance tiers): per player, adds fish nearest first until the fish
 * count and part budgets are full. New fish only within the show distance; already visible fish are
 * kept up to the keep distance so they don't flicker at the edge. Pure math.
 */
public final class ViewBudget {

    /**
     * One candidate.
     *
     * @param distanceSq squared distance to the viewer
     * @param parts rig part count
     * @param shown whether it was shown to this viewer in the last selection
     * @param priority scripted fish (first regardless of distance)
     */
    public record Candidate(
            UUID id, double distanceSq, int parts, boolean shown, boolean priority) {}

    private ViewBudget() {}

    public static List<UUID> select(
            List<Candidate> candidates, int maxCount, int maxParts, double show, double keep) {
        double show2 = show * show;
        double keep2 = keep * keep;
        List<Candidate> sorted = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.distanceSq() <= show2 || (c.shown() && c.distanceSq() <= keep2)) {
                sorted.add(c);
            }
        }
        sorted.sort(
                Comparator.comparing((Candidate c) -> !c.priority())
                        .thenComparingDouble(Candidate::distanceSq));
        List<UUID> result = new ArrayList<>();
        int parts = 0;
        for (Candidate c : sorted) {
            if (result.size() >= maxCount) {
                break;
            }
            if (parts + c.parts() > maxParts) {
                continue; // a smaller rig may still fit
            }
            parts += c.parts();
            result.add(c.id());
        }
        return result;
    }

    /**
     * Player part budget = min(base × effects intensity multiplier, Core limit − parts other
     * modules already show).
     *
     * @param otherParts non-Wildlife rig parts such as fishing shadows, aquariums and bosses
     */
    public static int partBudget(int base, double intensityScale, int coreBudget, int otherParts) {
        int scaled = (int) Math.round(base * intensityScale);
        return Math.max(0, Math.min(scaled, coreBudget - Math.max(0, otherParts)));
    }

    public static int countBudget(int base, double scale) {
        return Math.max(0, (int) Math.round(base * scale));
    }

    /** Distance tier. */
    public enum Tier {
        /** Up to 16 blocks: send every 2 ticks, all keyframes. */
        NEAR,
        /** 16~40 blocks: send every 4 ticks, special action particles skipped. */
        MID,
        /** Beyond 40 blocks: hidden, simulated at 2Hz. */
        FAR
    }

    public static Tier tier(double nearestDistance, double near, double show) {
        if (nearestDistance <= near) {
            return Tier.NEAR;
        }
        return nearestDistance <= show ? Tier.MID : Tier.FAR;
    }
}
