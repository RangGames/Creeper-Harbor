package wiki.creeper.harbor.core.api;

import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Furniture and custom blocks (proposed, furniture/furniture.yml · furniture/blocks.yml). Main
 * thread.
 *
 * <ul>
 *   <li>Furniture: placed by right-clicking a block while holding the item (BlockPlaceEvent checks
 *       protected regions). Seatable furniture is sat on with right-click; sneak + right-click
 *       picks it up ({@link wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent})
 *   <li>Custom blocks: note block items, so they are placed and broken like vanilla. Placing sets
 *       the note block state; breaking drops that block's item
 * </ul>
 */
public interface FurnitureService {

    /** One furniture item (empty if there is no definition). */
    Optional<ItemStack> furnitureItem(HarborId furniture);

    /** Custom block item (empty if there is no definition). */
    Optional<ItemStack> blockItem(HarborId block, int amount);

    /** If this is a furniture item, its furniture ID. */
    Optional<HarborId> furnitureOf(@Nullable ItemStack stack);

    /** If this is a custom block item, its block ID. */
    Optional<HarborId> blockOf(@Nullable ItemStack stack);

    /** If this is a placed custom block, its block ID. */
    Optional<HarborId> blockAt(Block block);
}
