package wiki.creeper.harbor.jobs.content;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.JobTier;

/**
 * The jobs section of balance.yml (docs 20 2.3). Missing values default to the design document
 * values.
 */
public record JobsBalance(Balance balance) {

    /** Catch-up stage: from season week week onward, if level ≤ median − gap, exp_gain +bonus. */
    public record CatchUp(int week, int gap, double bonus) {}

    public double expCoef() {
        return balance.number("jobs.exp_formula.coef", 2);
    }

    public double expPow() {
        return balance.number("jobs.exp_formula.exp", 2.05);
    }

    public int expRound() {
        return balance.integer("jobs.exp_formula.round", 10);
    }

    public int maxLevel() {
        return balance.integer("jobs.max_level", 70);
    }

    public int levelCap(JobTier tier) {
        int fallback =
                switch (tier) {
                    case FIRST -> 30;
                    case SECOND -> 60;
                    case THIRD -> 70;
                };
        return balance.integer("jobs.tier_level_cap." + tier.level(), fallback);
    }

    public int qualityCap(JobTier tier) {
        return balance.integer("jobs.quality_cap." + tier.level(), tier.level() + 2);
    }

    public long advanceCost(JobTier target) {
        return (long)
                balance.number(
                        "jobs.advance_cost." + target.level(),
                        target == JobTier.SECOND ? 15_000 : 90_000);
    }

    public int advanceLevel(JobTier target) {
        return levelCap(JobTier.of(target.level() - 1));
    }

    public long lineChangeCost(JobTier tier) {
        return (long)
                balance.number(
                        "jobs.line_change_cost." + tier.level(),
                        tier == JobTier.THIRD ? 80_000 : 30_000);
    }

    public double refundRatio() {
        return balance.number("jobs.refund_ratio", 0.8);
    }

    public long commonResetCost() {
        return (long) balance.number("jobs.common_reset_cost", 5_000);
    }

    public int regionDecayGap() {
        return balance.integer("jobs.region_decay.gap", 15);
    }

    public double regionDecayMult() {
        return balance.number("jobs.region_decay.mult", 0.5);
    }

    public int tierFirstQualityBonus() {
        return balance.integer("jobs.tier_first_quality_bonus", 2);
    }

    /**
     * Tier locks: tier 2 needs 8 and tier 3 needs 30 or more invested in that tree ('10. Skill
     * tree' chapter 1).
     */
    public int tierPoints(int tier) {
        return switch (tier) {
            case 2 -> balance.integer("jobs.tier_points.2", 8);
            case 3 -> balance.integer("jobs.tier_points.3", 30);
            default -> 0;
        };
    }

    public int capstoneRequires() {
        return balance.integer("jobs.capstone_requires", 60);
    }

    public int otherLineLimit() {
        return balance.integer("jobs.other_line_limit", 40);
    }

    public List<CatchUp> catchUps() {
        List<CatchUp> result = new ArrayList<>();
        for (String key : balance.keys("jobs.catchup")) {
            String base = "jobs.catchup." + key + ".";
            result.add(
                    new CatchUp(
                            balance.integer(base + "week", 99),
                            balance.integer(base + "gap", 10),
                            balance.number(base + "bonus", 0)));
        }
        return result;
    }

    /**
     * Level curve check values: level → cumulative EXP to reach that level ('7. Balance design'
     * 3.1).
     */
    public List<int[]> curveChecks() {
        List<int[]> result = new ArrayList<>();
        for (String key : balance.keys("jobs.curve_check")) {
            try {
                result.add(
                        new int[] {
                            Integer.parseInt(key), balance.integer("jobs.curve_check." + key, -1)
                        });
            } catch (NumberFormatException e) {
                // Malformed keys are reported as errors by the check
                result.add(new int[] {-1, -1});
            }
        }
        return result;
    }
}
