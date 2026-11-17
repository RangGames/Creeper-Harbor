package wiki.creeper.harbor.fishing.roll;

import java.util.Map;
import wiki.creeper.harbor.common.model.Grade;

/**
 * 판정에 쓰는 보정 값 (메인 스레드에서 Core 보정자로 미리 계산해 넘긴다).
 *
 * @param gradeBonus 희귀 이상 등급별 보정 합 (예: RARE → 0.10). 상한은 RollEngine이 적용
 * @param uncaughtWeight 도감 미등록 어종 가중치 보정 (도감 사냥꾼)
 * @param legendPityStartOffset 전설 천장 시작점 보정 (전설의 숨결 −30·−60)
 * @param legendChanceMult 전설 확률 배율 (전설의 기척 ×2)
 * @param speciesMult 이벤트 어종 배율 (물고기 대이동 ×3)
 */
public record RollModifiers(
        Map<Grade, Double> gradeBonus,
        double uncaughtWeight,
        int legendPityStartOffset,
        double legendChanceMult,
        Map<String, Double> speciesMult) {

    public static final RollModifiers NONE = new RollModifiers(Map.of(), 0, 0, 1, Map.of());
}
