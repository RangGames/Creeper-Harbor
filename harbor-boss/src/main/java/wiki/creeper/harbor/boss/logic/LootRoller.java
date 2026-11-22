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
 * Loot rolling (docs 20 10.4 step 2). SplittableRandom; the seed is recorded per encounter so the
 * same seed gives the same result. Raid pity: a pity-target drop is guaranteed on the pity_after-th
 * attempt and resets to 0 when obtained.
 */
public final class LootRoller {

    /** One rolled drop. */
    public record Rolled(ItemId item, int count, boolean byPity) {}

    /**
     * @param pity pity-target drop → counter after applying this result (0 if obtained)
     */
    public record Result(List<Rolled> items, Map<ItemId, Integer> pity) {}

    private LootRoller() {}

    /** Per-player seed (encounter seed + UUID). */
    public static long seed(long encounterSeed, UUID player) {
        long h =
                player.getMostSignificantBits() * 0x9E3779B97F4A7C15L
                        ^ player.getLeastSignificantBits();
        return encounterSeed ^ Long.rotateLeft(h, 17);
    }

    /**
     * @param chanceBonus value added to every drop chance (quartermaster bonus)
     * @param pity current pity counter (0 if none)
     * @param pityAfter 0 means no pity
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

    /** Gold within the range (as is if min = max). */
    public static long gold(long min, long max, SplittableRandom random) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return random.nextLong(min, max + 1);
    }
}
