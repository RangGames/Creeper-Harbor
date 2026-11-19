package wiki.creeper.harbor.core.furniture;

import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FurnitureService;

/** {@link FurnitureService} 구현: 가구·커스텀 블록 관리자를 묶는다. */
public final class FurnishingService implements FurnitureService {

    private final FurnitureManager furniture;
    private final CustomBlockManager blocks;

    public FurnishingService(FurnitureManager furniture, CustomBlockManager blocks) {
        this.furniture = furniture;
        this.blocks = blocks;
    }

    @Override
    public Optional<ItemStack> furnitureItem(HarborId id) {
        return furniture.def(id).map(furniture::item);
    }

    @Override
    public Optional<ItemStack> blockItem(HarborId id, int amount) {
        return blocks.def(id).map(def -> blocks.item(def, amount));
    }

    @Override
    public Optional<HarborId> furnitureOf(@Nullable ItemStack stack) {
        return furniture.idOf(stack);
    }

    @Override
    public Optional<HarborId> blockOf(@Nullable ItemStack stack) {
        return blocks.idOf(stack);
    }

    @Override
    public Optional<HarborId> blockAt(Block block) {
        return blocks.at(block).map(CustomBlockDef::id);
    }
}
