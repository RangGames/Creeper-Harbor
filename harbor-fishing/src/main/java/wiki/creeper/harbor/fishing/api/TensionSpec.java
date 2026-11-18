package wiki.creeper.harbor.fishing.api;

import net.kyori.adventure.text.Component;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Boss 앵커 등 외부에서 여는 텐션 미니게임 설정 ({@link FishingService#openTension}, docs 20 3.2 추가 제안).
 *
 * @param title 보스바 이름
 * @param grade 보스바 색과 기본 텐션 수치를 가져올 등급 (balance.yml fishing.minigame.<등급>)
 * @param hp 상대 체력 (0 이하이면 등급 기본값)
 * @param tensionRiseMult 등급 텐션 상승에 곱하는 배율 (보스 페이즈마다 바꿀 수 있게)
 * @param extraLineStrength 장비 줄 강도에 더하는 값 (Boss가 anchor_tension_limit 등을 반영)
 * @param patterns 패턴 사용 여부 (false면 Boss가 {@link TensionSession#addTension}으로 직접 흔든다)
 * @param endOnCaught 체력이 0이 되면 CAUGHT로 끝낼지 (false면 0에서 멈추고 Boss가 {@link TensionSession#close}로 끝낸다)
 */
public record TensionSpec(
        Component title,
        Grade grade,
        double hp,
        double tensionRiseMult,
        double extraLineStrength,
        boolean patterns,
        boolean endOnCaught) {

    public TensionSpec {
        if (tensionRiseMult <= 0) {
            throw new IllegalArgumentException("tensionRiseMult > 0");
        }
    }

    /** 등급 기본값 그대로. */
    public static TensionSpec of(Component title, Grade grade) {
        return new TensionSpec(title, grade, 0, 1, 0, true, true);
    }
}
