package wiki.creeper.harbor.quest.api;

import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Highest unlocked region (request gold, board candidates, hint region). The default implementation
 * is Quest (regions.yml unlock level + unlock quest, ordered by board.yml gold_by_region). If World
 * registers this interface in the ServicesManager with a higher priority, that implementation is
 * used.
 */
@FunctionalInterface
public interface RegionProgressProvider {

    /** Online player (main thread). Empty if none. */
    Optional<RegionId> highestUnlockedRegion(UUID player);
}
