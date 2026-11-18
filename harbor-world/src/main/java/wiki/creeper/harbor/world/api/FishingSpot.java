package wiki.creeper.harbor.world.api;

import java.time.Instant;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 물고기 떼 포인트 (docs 20 8.4). 10~20분마다 다른 후보로 옮긴다. hidden이면 숨은 낚시터 (탐험 2차 '숨은 낚시터 감지'로만 보인다).
 *
 * @param id 보정자 출처로도 쓰는 ID (spot:<지역>_<번호> 또는 spot:<숨은 낚시터 ID>)
 * @param world 콘텐츠 월드 이름
 */
public record FishingSpot(
        String id,
        RegionId region,
        String world,
        double x,
        double y,
        double z,
        boolean hidden,
        Instant movesAt) {

    public double distanceSquared(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz;
    }
}
