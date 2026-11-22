package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Sea events (docs 20 8.2 SeaEventService). The scheduler runs on the one server holding
 * harbor:lock:event, and all servers share the state received over harbor:event. Used by Wildlife
 * (great fish migration density) and Fishing (fish_effects arrive separately as SeaEventMessage).
 * Main thread only.
 */
public interface SeaEventService {

    /** In progress (ACTIVE). */
    List<ActiveSeaEvent> active();

    /** In-progress events that apply to this region. */
    List<ActiveSeaEvent> active(RegionId region);

    /** Announced (WARN). */
    List<ActiveSeaEvent> upcoming();

    /** Whether this location is inside active storm waters. */
    Optional<ActiveSeaEvent> stormAt(Location location);
}
