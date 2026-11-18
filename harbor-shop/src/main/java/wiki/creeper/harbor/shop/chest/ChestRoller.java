package wiki.creeper.harbor.shop.chest;

import java.util.List;
import java.util.function.IntSupplier;
import wiki.creeper.harbor.shop.content.ChestDef;

/**
 * 진주 보물함 추첨 (docs 20 12.4, 서버 없이 테스트한다). 0~999,999 정수 하나를 뽑아 누적 확률표(백만분율)에서 고른다. 천장 카운터가 count−1이면
 * (이번이 count번째) 최상위 등급이 나오지 않았을 때 최상위 등급 안에서만 다시 고른다. 최상위를 얻으면 카운터 0.
 */
public final class ChestRoller {

    /**
     * 추첨 한 번의 결과.
     *
     * @param roll 첫 추첨 값 (0~999,999, chest_open_log.roll)
     * @param pityHit 천장으로 확정됐는지
     */
    public record Outcome(
            ChestDef.Entry entry, int roll, boolean pityHit, int pityBefore, int pityAfter) {

        public boolean top(ChestDef chest) {
            return entry.grade().isAtLeast(chest.pity().grade());
        }
    }

    private ChestRoller() {}

    /**
     * @param random 0 이상 {@link ChestDef#PPM_TOTAL} 미만 정수를 주는 난수 (운영은 SecureRandom)
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

    /** 누적 확률표에서 고른다. value는 0 이상 total 미만. */
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
