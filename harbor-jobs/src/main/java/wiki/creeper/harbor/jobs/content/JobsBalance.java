package wiki.creeper.harbor.jobs.content;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.JobTier;

/** balance.yml의 jobs 절 (docs 20 2.3). 값이 없으면 기획 문서의 값을 기본값으로 쓴다. */
public record JobsBalance(Balance balance) {

    /** 캐치업 단계: 시즌 week주차 이상이고 레벨이 중앙값 − gap 이하면 exp_gain +bonus. */
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

    /** 단 잠금: 그 트리에 2단은 8, 3단은 30 이상 투자 ('10. 스킬 트리' 1장). */
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

    /** 레벨 곡선 검산값: 레벨 → 그 레벨 도달 누적 EXP ('7. 밸런스 기획' 3.1). */
    public List<int[]> curveChecks() {
        List<int[]> result = new ArrayList<>();
        for (String key : balance.keys("jobs.curve_check")) {
            try {
                result.add(
                        new int[] {
                            Integer.parseInt(key), balance.integer("jobs.curve_check." + key, -1)
                        });
            } catch (NumberFormatException e) {
                // 형식이 틀린 키는 검사에서 오류로 보고한다
                result.add(new int[] {-1, -1});
            }
        }
        return result;
    }
}
