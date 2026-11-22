package wiki.creeper.harbor.fishing.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * State of one trap (read-only copy).
 *
 * @param slots base trap slots + purchased extra slots (the job bonus trap_slots is added when
 *     computing)
 * @param stored number of catches not yet harvested
 * @param nextCycleAt time the next cycle completes (based on the owner's stats, null if unknown)
 */
public record TrapInfo(
        long id,
        UUID owner,
        RegionId region,
        String world,
        int x,
        int y,
        int z,
        ItemId item,
        int tier,
        int slots,
        int extraSlots,
        int bait,
        int stored,
        Instant lastCalc,
        Instant lastHarvest,
        @Nullable Instant nextCycleAt) {}
