package wiki.creeper.harbor.world.api;

import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 지역 요약 (regions.yml + world/regions.yml).
 *
 * @param world 콘텐츠의 월드 이름 (harbor_main · harbor_nether · harbor_end)
 * @param order 해금 순서 (1 항구 … 7 엔드)
 * @param hourlyGold 수동 낚시 시간당 골드 ('7. 밸런스 기획' 3.2)
 * @param areas 세부 구역 ID
 * @param server 이 지역 월드를 돌리는 서버 ID (null = 지금 서버)
 */
public record RegionInfo(
        RegionId id,
        String name,
        String world,
        String biome,
        int order,
        int recLevel,
        int hourlyGold,
        List<String> areas,
        @Nullable String server) {

    public RegionInfo {
        areas = List.copyOf(areas);
    }
}
