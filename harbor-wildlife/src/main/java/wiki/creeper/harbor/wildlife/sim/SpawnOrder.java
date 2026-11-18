package wiki.creeper.harbor.wildlife.sim;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;

/**
 * 메인 스레드가 정해 계산 스레드로 넘기는 생성 주문. 개체 ID는 메인이 미리 만든다 (spawnScripted가 바로 돌려줄 수 있게).
 *
 * @param ids 만들 개체 (첫 번째가 무리 대장)
 * @param center 무리 중심 (매질 안). 나머지는 주변 1.5블록 안 매질 칸에 흩어 놓는다
 * @param school 무리 ID (혼자면 null)
 * @param scripted 연출 개체면 행동, 자연 개체면 null
 * @param path 경로 무리 지점 (비면 없음)
 * @param tag 물고기 떼 포인트 ID (그 자리 무리면)
 * @param region 집 지역 이름 (경계 검사용)
 * @param airBaseY 공중 개체의 섬 기준 높이
 */
public record SpawnOrder(
        List<UUID> ids,
        SpeciesProfile profile,
        String world,
        Vec3 center,
        @Nullable UUID school,
        @Nullable ScriptedBehaviour scripted,
        List<Vec3> path,
        @Nullable String tag,
        @Nullable String region,
        double airBaseY) {

    public SpawnOrder {
        ids = List.copyOf(ids);
        path = List.copyOf(path);
    }
}
