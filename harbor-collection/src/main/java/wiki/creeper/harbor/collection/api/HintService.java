package wiki.creeper.harbor.collection.api;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Hints (docs 20 4.4). Stage 0 silhouette·region → 1 time of day → 2 weather·bait → 3
 * zone·moon·job·hint_text. Effective stage is max(stored stage, collection skill line "spawn
 * condition hint" rank).
 */
public interface HintService {

    int MAX_LEVEL = 3;

    int level(UUID player, FishId fish);

    /** Raises the stage only if higher than the stored one. Returns true if raised. */
    boolean grant(UUID player, FishId fish, int level, String source);

    /**
     * In the highest unlocked region, raises one unregistered species by one stage, preferring
     * lower stage → lower grade. If that region is fully registered, moves down to lower regions.
     * If there is nothing to give, returns an empty Optional (Quest gives a substitute reward).
     */
    Optional<FishId> grantRandom(UUID player, @Nullable RegionId region, String source);
}
