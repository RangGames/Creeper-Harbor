package wiki.creeper.harbor.wildlife.api;

import org.jspecify.annotations.Nullable;

/**
 * {@link WildlifeService#spawnScripted}의 행동 (이벤트·연출용).
 *
 * @param action 처음에 한 번 재생할 동작 (behaviours.yml actions 이름, 예: leap). null이면 없음
 * @param hold 참이면 그 자리에 머문다 (CRUISE 목표점을 고르지 않고 제자리에서 idle·고유 동작만)
 * @param lifetimeSeconds 이 시간이 지나면 사라진다. 0 이하면 다른 개체처럼 정리 규칙(56블록·청크 언로드)만 따른다
 * @param ignoreThreat 참이면 위협에 반응하지 않는다 (연출 도중 도망가지 않게)
 */
public record ScriptedBehaviour(
        @Nullable String action, boolean hold, int lifetimeSeconds, boolean ignoreThreat) {

    /** 보통 개체처럼 헤엄친다 (사라지는 시간 없음). */
    public static final ScriptedBehaviour CRUISE = new ScriptedBehaviour(null, false, 0, false);

    /** 그 자리에서 동작 하나를 보여 주고 일정 시간 머문다. */
    public static ScriptedBehaviour show(@Nullable String action, int seconds) {
        return new ScriptedBehaviour(action, true, seconds, true);
    }
}
