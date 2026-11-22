package wiki.creeper.harbor.world.api;

import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Treasure maps (docs 20 8.4 'treasure maps'). Called by Fishing's TreasureDropHook implementation.
 * Boss loot (sea boss treasure map 30%) is made with it too. Each map has a uid, and the
 * treasure_claim unique key lets each map be claimed only once. Main thread only.
 */
public interface TreasureService {

    /**
     * Picks one treasure location in the region and a tier and makes the map item (the caller hands
     * it out). Empty if there is no location.
     */
    Optional<ItemStack> createMap(Player player, RegionId region);

    /** Makes one with a given tier (ghost ship ghost etc.). */
    Optional<ItemStack> createMap(Player player, RegionId region, String tier);

    boolean isTreasureMap(ItemStack stack);
}
