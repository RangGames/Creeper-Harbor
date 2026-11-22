package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;

/**
 * Regions (docs 20 8.2 RegionService). Region detection uses the chunk index; entry conditions,
 * push-back and first-visit records are handled by World. Look it up in the ServicesManager. Use on
 * the main thread (methods returning a CompletableFuture from anywhere; results complete on the
 * main thread).
 *
 * <p>Used by Economy (recipe 'visit' unlocks, per-region occupancy), Quest (visit objectives) and
 * Wildlife (regions and sub-areas).
 */
public interface RegionService {

    /** In unlock order. */
    List<RegionInfo> regions();

    Optional<RegionInfo> region(RegionId id);

    /** Region at the location (world name with balance.yml world_alias applied). */
    Optional<RegionInfo> regionAt(Location location);

    /** Sub-area IDs at the location (same values as the fish species area). */
    List<String> areasAt(Location location);

    /**
     * Fishing key at the bobber position (same result as the RegionKeyResolver registered with
     * Fishing).
     */
    Optional<RegionKeyResolver.RegionKey> fishingKey(Location bobber);

    /** The region an online player is currently in. */
    Optional<RegionId> current(UUID player);

    /** Regions visited this season (while online). */
    Set<RegionId> visited(UUID player);

    /** Whether visited this season (while online, otherwise false). */
    boolean hasVisited(UUID player, RegionId region);

    /** Checks the DB for offline players too. */
    CompletableFuture<Boolean> loadVisited(UUID player, RegionId region);

    /** Entry condition check. */
    UnlockStatus unlockStatus(Player player, RegionId region);

    /**
     * Highest unlocked region (ignores the vehicle condition). Used for "highest unlocked region"
     * criteria such as Quest request rewards and the Fishing trap cap ('13. crafting · shop prices'
     * chapter 6).
     */
    RegionInfo highestUnlocked(Player player);

    /** Player count per region on this server. */
    int occupancy(RegionId region);

    /**
     * Player count per region across all servers (Redis, aggregated every minute). For the Economy
     * price S₀.
     */
    CompletableFuture<Map<RegionId, Integer>> occupancyAll();

    /**
     * Sends the player to the region's safe point (if the region's world is on another server,
     * moves servers via ProxyBridge and the arrival server places them). Entry conditions are not
     * checked.
     *
     * @return true if the move started
     */
    boolean travel(Player player, RegionId region, String reason);
}
