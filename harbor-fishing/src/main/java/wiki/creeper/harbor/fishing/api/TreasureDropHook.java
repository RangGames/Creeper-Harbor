package wiki.creeper.harbor.fishing.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 잡템으로 보물 지도가 뽑혔을 때 (docs 20 3.2, 추가 제안). World가 {@link FishingService#registerTreasureHook}로 등록해
 * 지도 아이템을 만들어 준다 (보물 위치·중복 수령 방지는 World 몫). 등록되지 않았으면 보물 지도는 나오지 않는다. 확률은 Fishing이
 * fishing/junk.yml의 treasure 항목 × (1 + treasure_map_chance)로 판정한다. 메인 스레드에서 불린다.
 */
@FunctionalInterface
public interface TreasureDropHook {

    /**
     * @param player 낚은 사람
     * @param bobber 찌 위치
     * @param region 낚은 지역
     */
    void onTreasureMap(Player player, Location bobber, RegionId region);
}
