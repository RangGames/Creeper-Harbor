package wiki.creeper.harbor.fishing.api;

import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * 텐션 모델 공용 세션 (docs 20 3.2 openTension, Boss 앵커용). Fishing의 미니게임 엔진(20Hz, Shift 감기 + A/D 방향, 텐션
 * 게이지·보스바 UI, 텐션 0.85 이상이면 화면 가장자리 경고)을 그대로 쓰고, 결과만 Boss가 받는다. 아이템·EXP·미끼는 다루지 않는다. 메인 스레드 전용.
 *
 * <p>텐션 T는 0~1 (1 = 줄 끊김), 체력 비율은 1 → 0. 플레이어가 나가거나 죽으면 ESCAPED, {@link #close}로 끝내면 그 결과로 끝난다.
 */
public interface TensionSession {

    UUID player();

    double tension();

    /** 상대 체력 비율 (1 = 가득). */
    double hpRatio();

    /** 전설 3페이즈처럼 체력 비율로 정해지는 페이즈 (1부터). */
    int phase();

    boolean isActive();

    /** 진행 중이면 null. */
    @Nullable MinigameResult result();

    /** 보스 패턴: 텐션을 더한다 (음수면 줄어듦). */
    void addTension(double delta);

    /** 작살 등 외부 피해: 최대 체력 대비 비율만큼 체력을 줄인다. */
    void damage(double fraction);

    /** 끝났을 때 한 번 불린다 (이미 끝났으면 바로 불린다). */
    void onEnd(Consumer<MinigameResult> listener);

    /** 외부에서 끝낸다 (보스 처치·도주·시간 초과). 이미 끝났으면 무시한다. */
    void close(MinigameResult result);
}
