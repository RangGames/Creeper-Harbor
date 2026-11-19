package wiki.creeper.harbor.core.api;

import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 가구·커스텀 블록 (추가 제안, furniture/furniture.yml · furniture/blocks.yml). 메인 스레드.
 *
 * <ul>
 *   <li>가구: 아이템을 들고 블록을 우클릭하면 설치 (BlockPlaceEvent로 보호 구역 확인). 앉을 수 있는 가구는 우클릭으로 앉고, 웅크리고 우클릭하면 회수
 *       ({@link wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent})
 *   <li>커스텀 블록: 노트블록 아이템이라 바닐라처럼 놓고 부순다. 놓을 때 노트블록 상태를 바꾸고, 부수면 그 블록 아이템이 떨어진다
 * </ul>
 */
public interface FurnitureService {

    /** 가구 아이템 1개 (정의가 없으면 빈 값). */
    Optional<ItemStack> furnitureItem(HarborId furniture);

    /** 커스텀 블록 아이템 (정의가 없으면 빈 값). */
    Optional<ItemStack> blockItem(HarborId block, int amount);

    /** 가구 아이템이면 그 가구 ID. */
    Optional<HarborId> furnitureOf(@Nullable ItemStack stack);

    /** 커스텀 블록 아이템이면 그 블록 ID. */
    Optional<HarborId> blockOf(@Nullable ItemStack stack);

    /** 놓인 커스텀 블록이면 그 블록 ID. */
    Optional<HarborId> blockAt(Block block);
}
