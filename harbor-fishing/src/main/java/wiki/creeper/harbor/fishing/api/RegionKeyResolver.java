package wiki.creeper.harbor.fishing.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 찌 위치 → 낚시 지역 키 확장 지점 (docs 20 3.2, 추가 제안). World가 {@link FishingService#registerRegionResolver}로
 * 등록한다. 등록되지 않았으면 Fishing은 regions.yml의 경계만으로 지역을 정하고, 세부 구역(어종 area) 조건은 검사하지 않는다. Fishing은 World를
 * 부르지 않는다. 메인 스레드에서 캐스팅마다 불리므로 가볍게 구현한다 (목표 0.05ms).
 */
@FunctionalInterface
public interface RegionKeyResolver {

    /** 지역을 정할 수 없으면 빈 값 (그 자리에서는 낚시 불가). 위임하려면 {@code null} 대신 {@link Optional#empty()}를 돌려준다. */
    Optional<RegionKey> resolve(Location bobber);

    /**
     * 낚시 지역 키.
     *
     * @param region 지역 (regions.yml)
     * @param biomeKey 바이옴 키 (예: harbor:ash_trench, 분위기·통계용)
     * @param areas 세부 구역 ID (어종 area 조건과 맞춤, 예: lighthouse·cave_lake)
     * @param school 물고기 떼 위
     * @param spotSource 물고기 떼·숨은 낚시터의 보정자 출처 (World가 spot:으로 등록한 것, 없으면 null)
     */
    record RegionKey(
            RegionId region,
            String biomeKey,
            List<String> areas,
            boolean school,
            @Nullable String spotSource) {

        public RegionKey {
            areas = List.copyOf(areas);
        }
    }
}
