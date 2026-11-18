package wiki.creeper.harbor.quest.logic;

import wiki.creeper.harbor.quest.content.ProgressRules;

/** 클라이맥스 개방 판정 (docs 20 6.4): 기부율 ≥ 기준 + 7장 클리어 ≥ max(최소 인원, 활동 유저 × 비율). */
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
