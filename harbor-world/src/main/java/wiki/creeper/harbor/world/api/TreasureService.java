package wiki.creeper.harbor.world.api;

import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 보물 지도 (docs 20 8.4 '보물 지도'). Fishing의 TreasureDropHook 구현이 이것을 부른다. 보스 전리품(해역 보스 보물 지도 30%)도 이걸로
 * 만든다. 지도마다 uid가 있고, 수령은 treasure_claim 유니크로 지도당 한 번만 된다. 메인 스레드 전용.
 */
public interface TreasureService {

    /** 지역의 보물 장소 하나와 등급을 골라 지도 아이템을 만든다 (지급은 호출한 쪽). 장소가 없으면 빈 값. */
    Optional<ItemStack> createMap(Player player, RegionId region);

    /** 등급을 정해서 만든다 (유령선 ghost 등). */
    Optional<ItemStack> createMap(Player player, RegionId region, String tier);

    boolean isTreasureMap(ItemStack stack);
}
