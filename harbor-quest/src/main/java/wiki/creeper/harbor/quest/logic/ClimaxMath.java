package wiki.creeper.harbor.quest.logic;

import wiki.creeper.harbor.quest.content.ProgressRules;

/**
 * Climax unlock check (docs 20 6.4): donation rate ≥ threshold + ch. 7 clears ≥ max(minimum count,
 * active players × ratio).
 */
public final class ClimaxMath {

    private ClimaxMath() {}

    public static int requiredClears(ProgressRules rules, long activePlayers) {
        long byRatio = (long) Math.ceil(activePlayers * rules.clearRatio());
        return (int) Math.max(rules.minClears(), byRatio);
    }

    public static boolean ready(
            ProgressRules rules, double donationRate, long clears, long activePlayers) {
        return donationRate + 1e-9 >= rules.donationRate()
                && clears >= requiredClears(rules, activePlayers);
    }
}
