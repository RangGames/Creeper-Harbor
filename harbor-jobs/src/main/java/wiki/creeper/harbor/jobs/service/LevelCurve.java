package wiki.creeper.harbor.jobs.service;

import wiki.creeper.harbor.jobs.content.JobsBalance;

/**
 * 레벨 곡선: Lv L → L+1 필요 EXP = coef × L^exp (round 단위 반올림, '7. 밸런스 기획' 3.1). Lv1→2는 0이라 첫 경험치를 받는 순간
 * Lv2가 된다 (튜토리얼 첫 포획 연출).
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

    /** level → level+1 필요 EXP. 최대 레벨이면 0. */
    public long need(int level) {
        return level < 1 || level >= need.length ? 0 : need[level];
    }

    /** 그 레벨에 도달하는 데 필요한 누적 EXP. */
    public long cumulative(int level) {
        return cumulative[Math.clamp(level, 1, need.length - 1)];
    }
}
