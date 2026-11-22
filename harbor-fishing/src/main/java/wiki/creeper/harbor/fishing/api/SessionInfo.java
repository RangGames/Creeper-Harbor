package wiki.creeper.harbor.fishing.api;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Snapshot of one player's fishing session (read-only copy, {@link FishingService#sessionInfo}).
 *
 * @param fish species rolled for the current cast (null before the roll or when idle)
 * @param bitePending whether a bite is waiting to be hooked (state BITE)
 * @param minigame whether the catch minigame is running (state MINIGAME)
 * @param tensionSession whether a tension session opened through {@link FishingService#openTension}
 *     is running
 * @param tension current line tension 0~1 of the minigame or tension session (0 if neither runs)
 * @param hpRatio remaining fish HP 1→0 of the minigame or tension session (1 if neither runs)
 * @param region region of the current cast (null when idle)
 * @param bait bait used by the current cast (null if none or idle)
 * @param forcedFish species forced for the next roll ({@link FishingService#forceNextFish}), null
 *     if none
 */
public record SessionInfo(
        SessionState state,
        @Nullable FishId fish,
        boolean bitePending,
        boolean minigame,
        boolean tensionSession,
        double tension,
        double hpRatio,
        @Nullable RegionId region,
        @Nullable ItemId bait,
        @Nullable FishId forcedFish) {}
