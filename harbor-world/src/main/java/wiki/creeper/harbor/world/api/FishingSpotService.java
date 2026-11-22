package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Fish school points (docs 20 8.2 FishingSpotService). Wildlife shows fish shoals at these spots.
 * Main thread only.
 */
public interface FishingSpotService {

    /** Nearest public fish school within the radius. */
    Optional<FishingSpot> spotNear(Location location, double radius);

    /** Includes hidden fishing spots (when this player has hidden spot detection). */
    Optional<FishingSpot> spotNear(Player viewer, Location location, double radius);

    List<FishingSpot> spots(RegionId region);
}
