package wiki.creeper.harbor.fishing.roll;

import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.MinigameResult;

/**
 * 미끼 소모 규칙 (docs 20 0.4, 3.4 '미끼 · 장비 소모'). 서버 API 없음 (단위 테스트).
 *
 * <ul>
 *   <li>일반 미끼: 챔질(HOOKED) 순간 1개. 성공·실패 모두 소모, 미리 감기·입질 놓침은 소모 없음. 성공하면 bait_save_chance로 1개 반환
 *   <li>전설의 미끼 (D8): 챔질 때 차감하지 않고, 전설 어종을 실제로 낚았을 때만 1개. 일반 어종이 걸리거나 미니게임에 실패하면 그대로 남는다
 *   <li>강제 이동(ABORTED): 일반 미끼 1개를 돌려준다 (전설의 미끼는 차감하지 않았으므로 돌려줄 것도 없음)
 * </ul>
 */
public final class BaitRule {

    private BaitRule() {}

    /** 챔질 순간 차감하는지. */
    public static boolean consumeOnHook(boolean legendBait) {
        return !legendBait;
    }

    /** 결과 순간 차감하는지 (전설의 미끼만 해당). */
    public static boolean consumeOnResult(boolean legendBait, MinigameResult result, Grade caught) {
        return legendBait && result == MinigameResult.CAUGHT && caught == Grade.LEGENDARY;
    }

    /** 결과 뒤 1개를 돌려주는지. saveRoll은 균등 난수, saveChance는 bait_save_chance. */
    public static boolean refund(
            boolean legendBait, MinigameResult result, double saveRoll, double saveChance) {
        if (legendBait) {
            return false;
        }
        return switch (result) {
            case CAUGHT -> saveRoll < saveChance;
            case ABORTED -> true;
            case LINE_BROKE, ESCAPED -> false;
        };
    }
}
