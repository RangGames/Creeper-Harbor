package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
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
 *
 * <p>Events: {@link wiki.creeper.harbor.core.api.event.FurniturePlaceEvent}, {@code
 * FurnitureInteractEvent}, {@code FurnitureSitEvent}, {@code FurnitureStandEvent}, {@code
 * FurnitureRemoveEvent}, {@code CustomBlockPlaceEvent}, {@code CustomBlockBreakEvent} (all in
 * {@code core.api.event}).
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

    // ------------------------------------------------------------------ Placed furniture

    /**
     * One placed piece of furniture.
     *
     * @param location center of the floor of the block it sits on (yaw = facing)
     * @param owner placer (null if placed by the API without an owner)
     * @param hitbox the click hitbox (holds the furniture data)
     */
    record Placed(HarborId id, Location location, @Nullable UUID owner, Interaction hitbox) {

        public Placed {
            location = location.clone();
        }

        @Override
        public Location location() {
            return location.clone();
        }
    }

    /** Placement result. */
    enum PlaceResult {
        OK,
        /** No such furniture definition. */
        UNKNOWN,
        /** A needed block (solid cells, light) is not replaceable. */
        NO_SPACE,
        /** Other furniture already stands in that block. */
        OCCUPIED,
        /** The acting player may not build there (BlockPlaceEvent was denied). */
        NO_PERMISSION,
        /** FurniturePlaceEvent was cancelled. */
        CANCELLED
    }

    /**
     * Places furniture with the same space checks as player placement (no protection check, no item
     * used). Fires FurniturePlaceEvent with no player.
     *
     * @param at the block the furniture stands in (its floor)
     * @param yaw facing, snapped to the furniture's rotation step
     * @param owner stored as the placer (may pick it up), or null
     */
    PlaceResult place(HarborId furniture, Location at, float yaw, @Nullable UUID owner);

    /**
     * Places furniture as that player: same space and protection checks (BlockPlaceEvent) as
     * placing by hand, and the player becomes the owner. No item is taken from the player.
     */
    PlaceResult placeAs(Player player, HarborId furniture, Location at, float yaw);

    /** Furniture standing in this location's block. */
    Optional<Placed> furnitureAt(Location at);

    /** Furniture of one of its entities (hitbox, model display or seat). */
    Optional<Placed> placedOf(Entity entity);

    /** Furniture within radius blocks of a location (loaded chunks only). */
    List<Placed> furnitureNear(Location at, double radius);

    /** Furniture in a loaded chunk. */
    List<Placed> furnitureIn(Chunk chunk);

    /**
     * Removes furniture without a player (no protection check, no FurnitureRemoveEvent): model,
     * hitbox, barrier and light blocks; a seated player is dismounted.
     *
     * @param dropItem whether to drop the placement item at the furniture
     * @return the placement item (1, a copy), or empty if the entity is not furniture
     */
    Optional<ItemStack> remove(Entity furniture, boolean dropItem);
}
