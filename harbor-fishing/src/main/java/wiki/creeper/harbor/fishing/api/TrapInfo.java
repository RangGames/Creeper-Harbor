package wiki.creeper.harbor.fishing.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 통발 하나의 상태 (조회용 사본).
 *
 * @param slots 통발 기본 슬롯 + 구매한 확장 슬롯 (직업 보너스 trap_slots는 계산할 때 더한다)
 * @param stored 아직 수확하지 않은 어획 수
 * @param nextCycleAt 다음 주기가 차는 시각 (주인 스탯 기준, 알 수 없으면 null)
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
