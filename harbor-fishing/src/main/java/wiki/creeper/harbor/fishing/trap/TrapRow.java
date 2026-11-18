package wiki.creeper.harbor.fishing.trap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/** traps 행 (docs 14 3장 + 3.2 추가 열). 변경 불가 사본이라 어느 스레드에서 읽어도 된다. */
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
