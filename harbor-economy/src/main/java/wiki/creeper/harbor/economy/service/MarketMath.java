package wiki.creeper.harbor.economy.service;

import java.util.Map;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/** Market formulas (no server API; unit tested). */
public final class MarketMath {

    private MarketMath() {}

    /**
     * M = clamp(1 − k × (S / S₀ − 1), min, max). max if there are no sales ("7. Balance design"
     * 6.2).
     */
    public static double coefficient(long sales, double s0, EconomyBalance balance) {
        if (sales <= 0) {
            return balance.marketMax();
        }
        double m = 1 - balance.marketK() * (sales / Math.max(1e-9, s0) - 1);
        return Math.clamp(m, balance.marketMin(), balance.marketMax());
    }

    /**
     * Expected appearance share: the grade's share of base weights in that region × the species'
     * share of weights within the grade. Legendary and mythic are 0 (not in normal rolls).
     *
     * @param gradeWeights grade base weights (60 · 25 · 10 · 4)
     */
    public static double expectedShare(
            FishDef fish, Iterable<FishDef> regionFish, Map<Grade, Double> gradeWeights) {
        double gradeTotal = 0;
        double sameGradeWeight = 0;
        java.util.EnumSet<Grade> present = java.util.EnumSet.noneOf(Grade.class);
        for (FishDef f : regionFish) {
            present.add(f.grade());
            if (f.grade() == fish.grade()) {
                sameGradeWeight += f.weight();
            }
        }
        for (Grade g : present) {
            gradeTotal += gradeWeights.getOrDefault(g, 0.0);
        }
        double gradeShare =
                gradeTotal <= 0 ? 0 : gradeWeights.getOrDefault(fish.grade(), 0.0) / gradeTotal;
        return sameGradeWeight <= 0 ? 0 : gradeShare * fish.weight() / sameGradeWeight;
    }

    /**
     * Next-hour forecast next_coef (docs 20 7.4 step 5): subtracts the sales that will drop out of
     * the window in the next hour and assumes the sales pace of the last hour continues.
     *
     * @param leaving sales of the hour that drops out of the 24-hour window at the next hour mark
     * @param lastHour sales in the last hour
     */
    public static double forecast(
            long sales, long leaving, long lastHour, double s0, EconomyBalance balance) {
        return coefficient(Math.max(0, sales - leaving + lastHour), s0, balance);
    }

    /**
     * Bulk-sale slippage (docs 20 7.4, proposed): when selling more of one species than the
     * threshold, uses the average of the current M and "the M after this sale is reflected" instead
     * of M. Economy line "Bulk Trade" (bulk_sell_slip −0.15/rank) reduces the drop.
     *
     * @param sales current 24-hour sales volume S
     * @param count number of fish in this batch
     * @param slip sum of bulk_sell_slip modifiers (default 0; more negative means a smaller drop)
     */
    public static double bulkCoefficient(
            double current, long sales, int count, double s0, double slip, EconomyBalance balance) {
        if (count <= balance.bulkThreshold() || s0 <= 0) {
            return current;
        }
        double after = coefficient(sales + count, s0, balance);
        if (after >= current) {
            return current;
        }
        double average = (current + after) / 2;
        double factor = Math.clamp(1 + slip, 0.0, 1.0);
        return current - (current - average) * factor;
    }
}
