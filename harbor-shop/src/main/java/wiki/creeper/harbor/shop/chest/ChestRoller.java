package wiki.creeper.harbor.shop.chest;

import java.util.List;
import java.util.function.IntSupplier;
import wiki.creeper.harbor.shop.content.ChestDef;

/**
 * Pearl chest roll (docs 20 12.4, tested without a server). Draws one integer 0~999,999 and picks
 * from the cumulative odds table (parts per million). If the pity counter is count−1 (this is the
 * count-th open) and the top grade did not come up, re-picks only within the top grade. Getting the
 * top grade resets the counter to 0.
 */
public final class ChestRoller {

    /**
     * Result of one roll.
     *
     * @param roll first roll value (0~999,999, chest_open_log.roll)
     * @param pityHit whether it was guaranteed by pity
     */
    public record Outcome(
            ChestDef.Entry entry, int roll, boolean pityHit, int pityBefore, int pityAfter) {

        public boolean top(ChestDef chest) {
            return entry.grade().isAtLeast(chest.pity().grade());
        }
    }

    private ChestRoller() {}

    /**
     * @param random random source returning an integer from 0 inclusive to {@link
     *     ChestDef#PPM_TOTAL} exclusive (SecureRandom in production)
     */
    public static Outcome roll(ChestDef chest, int pityBefore, IntSupplier random) {
        int roll = bounded(random.getAsInt());
        ChestDef.Entry picked = pick(chest.entries(), roll, ChestDef.PPM_TOTAL);
        boolean pityHit = false;
        boolean top = picked.grade().isAtLeast(chest.pity().grade());
        if (!top && pityBefore + 1 >= chest.pity().count()) {
            List<ChestDef.Entry> tops = chest.topEntries();
            int sum = tops.stream().mapToInt(ChestDef.Entry::ppm).sum();
            long second = bounded(random.getAsInt());
            picked = pick(tops, (int) (second * sum / ChestDef.PPM_TOTAL), sum);
            pityHit = true;
            top = true;
        }
        return new Outcome(picked, roll, pityHit, pityBefore, top ? 0 : pityBefore + 1);
    }

    /** Picks from the cumulative odds table. value is from 0 inclusive to total exclusive. */
    static ChestDef.Entry pick(List<ChestDef.Entry> entries, int value, int total) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("구성품이 없습니다");
        }
        int v = Math.floorMod(value, Math.max(1, total));
        int cumulative = 0;
        for (ChestDef.Entry entry : entries) {
            cumulative += entry.ppm();
            if (v < cumulative) {
                return entry;
            }
        }
        return entries.getLast();
    }

    private static int bounded(int value) {
        return Math.floorMod(value, ChestDef.PPM_TOTAL);
    }
}
