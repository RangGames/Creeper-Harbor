package wiki.creeper.harbor.core.furniture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FurnitureService;

/** {@link FurnitureService} implementation: bundles the furniture and custom block managers. */
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

    @Override
    public PlaceResult place(HarborId id, Location at, float yaw, @Nullable UUID owner) {
        return furniture.placeAt(null, id, at, yaw, owner);
    }

    @Override
    public PlaceResult placeAs(Player player, HarborId id, Location at, float yaw) {
        return furniture.placeAt(player, id, at, yaw, player.getUniqueId());
    }

    @Override
    public Optional<Placed> furnitureAt(Location at) {
        return furniture.at(at);
    }

    @Override
    public Optional<Placed> placedOf(Entity entity) {
        return furniture.of(entity);
    }

    @Override
    public List<Placed> furnitureNear(Location at, double radius) {
        return furniture.near(at, radius);
    }

    @Override
    public List<Placed> furnitureIn(Chunk chunk) {
        return furniture.in(chunk);
    }

    @Override
    public Optional<ItemStack> remove(Entity entity, boolean dropItem) {
        return furniture.removeByApi(entity, dropItem);
    }
}
