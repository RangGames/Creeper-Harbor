package wiki.creeper.harbor.wildlife.sim;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.wildlife.api.WildState;

/**
 * 한 주기 계산 결과 (개체 하나). 메인 스레드가 리그에 반영한다.
 *
 * @param animation 지금 이어서 재생할 기본 동작 (swim · idle)
 * @param animationSpeed 재생 속도 배율 (빨리 헤엄치면 빠르게)
 * @param oneShot 마지막으로 시작한 한 번 재생 동작 (oneShotSeq가 바뀌면 새로 재생)
 * @param teleportSeq 순간 이동 번호 (바뀌면 보간 없이 옮긴다)
 * @param hidden 찌 아래로 잠겨 Fishing 그림자가 이어받은 상태 (그리지 않음)
 * @param phaseOffset 애니메이션 박자 (틱). 다시 보일 때 (서버 틱 + 이 값)으로 현재 프레임을 맞춘다
 * @param nearest 가장 가까운 플레이어 거리
 */
public record FishOut(
        UUID id,
        SpeciesProfile profile,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        WildState state,
        String animation,
        double animationSpeed,
        @Nullable String oneShot,
        int oneShotSeq,
        int teleportSeq,
        boolean hidden,
        @Nullable UUID school,
        boolean leader,
        boolean scripted,
        @Nullable String tag,
        @Nullable String region,
        int phaseOffset,
        double vx,
        double vy,
        double vz,
        double steerX,
        double steerY,
        double steerZ,
        double nearest) {

    public double distanceSq(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
