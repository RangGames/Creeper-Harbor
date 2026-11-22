package wiki.creeper.harbor.jobs.service;

import wiki.creeper.harbor.jobs.content.JobsBalance;

/**
 * Level curve: EXP needed for Lv L → L+1 = coef × L^exp (rounded to round, '7. Balance design'
 * 3.1). Lv1→2 is 0, so the first experience gained makes the player Lv2 (tutorial first catch
 * effect).
 */
public final class LevelCurve {

    private final long[] need;
    private final long[] cumulative;

    public LevelCurve(double coef, double pow, int round, int maxLevel) {
        need = new long[maxLevel + 1];
        cumulative = new long[maxLevel + 1];
        for (int level = 1; level <= maxLevel; level++) {
            need[level] =
                    level == maxLevel ? 0 : Math.round(coef * Math.pow(level, pow) / round) * round;
            cumulative[level] = level == 1 ? 0 : cumulative[level - 1] + need[level - 1];
        }
    }

    public static LevelCurve of(JobsBalance balance) {
        return new LevelCurve(
                balance.expCoef(), balance.expPow(), balance.expRound(), balance.maxLevel());
    }

    public int maxLevel() {
        return need.length - 1;
    }

    /** EXP needed for level → level+1. 0 at max level. */
    public long need(int level) {
        return level < 1 || level >= need.length ? 0 : need[level];
    }

    /** Cumulative EXP needed to reach that level. */
    public long cumulative(int level) {
        return cumulative[Math.clamp(level, 1, need.length - 1)];
    }
}
