package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.common.id.ItemId;

/**
 * 전리품 굴리기 (docs 20 10.4 2단계). SplittableRandom, 조우마다 시드를 기록해 같은 시드면 같은 결과가 나온다. 레이드 천장: 천장 대상 드롭을
 * pity_after번째 시도에서 확정하고, 얻으면 0으로.
 */
public final class LootRoller {

    /** 굴린 드롭 하나. */
    public record Rolled(ItemId item, int count, boolean byPity) {}

    /**
     * @param pity 천장 대상 드롭 → 이번 결과 반영 뒤 카운터 (얻으면 0)
     */
    public record Result(List<Rolled> items, Map<ItemId, Integer> pity) {}

    private LootRoller() {}

    /** 플레이어별 시드 (조우 시드 + UUID). */
    public static long seed(long encounterSeed, UUID player) {
        long h =
                player.getMostSignificantBits() * 0x9E3779B97F4A7C15L
                        ^ player.getLeastSignificantBits();
        return encounterSeed ^ Long.rotateLeft(h, 17);
    }

    /**
     * @param chanceBonus 모든 드롭 확률에 더하는 값 (보급관 보너스)
     * @param pity 지금 천장 카운터 (없으면 0)
     * @param pityAfter 0이면 천장 없음
     */
    public static Result roll(
            List<BossDef.Drop> drops,
            SplittableRandom random,
            double chanceBonus,
            Map<ItemId, Integer> pity,
            int pityAfter) {
        List<Rolled> items = new ArrayList<>();
        Map<ItemId, Integer> next = new HashMap<>();
        for (BossDef.Drop drop : drops) {
            double roll = random.nextDouble();
            int count =
                    drop.countMin() == drop.countMax()
                            ? drop.countMin()
                            : random.nextInt(drop.countMin(), drop.countMax() + 1);
            boolean pityTracked = drop.pity() && pityAfter > 0;
            int counter = pityTracked ? pity.getOrDefault(drop.item(), 0) : 0;
            boolean hit = roll < Math.min(1.0, drop.chance() + chanceBonus);
            boolean byPity = !hit && pityTracked && counter + 1 >= pityAfter;
            if (hit || byPity) {
                items.add(new Rolled(drop.item(), count, byPity));
            }
            if (pityTracked) {
                next.put(drop.item(), hit || byPity ? 0 : counter + 1);
            }
        }
        return new Result(List.copyOf(items), Map.copyOf(next));
    }

    /** 범위 안 골드 (min = max면 그대로). */
    public static long gold(long min, long max, SplittableRandom random) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return random.nextLong(min, max + 1);
    }
}
