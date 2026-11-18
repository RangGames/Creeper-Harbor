package wiki.creeper.harbor.wildlife.sim;

import java.util.UUID;
import wiki.creeper.harbor.common.rig.Vec3;

/**
 * 메인 스레드가 틱 끝에 복사해 넘기는 플레이어 상태 ('17-3' 9.4 스레드 1단계). 속도는 계산 스레드가 이전 위치와 비교해 구한다.
 *
 * @param world 실제 월드 이름
 * @param threat 위협이 될 수 있는지 (관전 모드·투명 관리자 제외)
 */
public record PlayerSnap(
        UUID id, String world, Vec3 pos, boolean sneaking, boolean boat, boolean threat) {}
