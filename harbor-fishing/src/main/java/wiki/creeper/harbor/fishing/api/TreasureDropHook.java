package wiki.creeper.harbor.fishing.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * When a treasure map is rolled as junk (docs 20 3.2, proposed). World registers it via {@link
 * FishingService#registerTreasureHook} and creates the map item (treasure location and duplicate
 * claim prevention are World's job). Without registration no treasure maps drop. Fishing rolls the
 * chance as the treasure entry of fishing/junk.yml × (1 + treasure_map_chance). Called on the main
 * thread.
 */
@FunctionalInterface
public interface TreasureDropHook {

    /**
     * @param player the player who fished it
     * @param bobber bobber location
     * @param region region fished in
     */
    void onTreasureMap(Player player, Location bobber, RegionId region);
}
