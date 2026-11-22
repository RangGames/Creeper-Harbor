package wiki.creeper.harbor.fishing.trap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * traps row (docs 14 chapter 3 + 3.2 extra columns). An immutable copy, so it can be read from any
 * thread.
 */
public record TrapRow(
        long id,
        UUID owner,
        int season,
        RegionId region,
        String world,
        int x,
        int y,
        int z,
        int tier,
        ItemId item,
        int slots,
        int extraSlots,
        int bait,
        Instant lastCalc,
        List<TrapCatch> contents,
        double value24h,
        Instant windowStart,
        Instant lastHarvest,
        Instant placedAt) {

    public TrapRow {
        contents = List.copyOf(contents);
    }
}
