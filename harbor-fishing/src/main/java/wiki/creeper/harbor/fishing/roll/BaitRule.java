package wiki.creeper.harbor.fishing.roll;

import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.MinigameResult;

/**
 * Bait consumption rules (docs 20 0.4, 3.4 "bait · gear consumption"). No server API (unit tests).
 *
 * <ul>
 *   <li>Normal bait: 1 at the moment of hooking (HOOKED). Consumed on success or failure; reeling
 *       early or missing the bite consumes none. bait_save_chance is judged at the moment of
 *       hooking (docs 20 17-1 3.4, 0.4): when it triggers nothing is deducted, whatever the result
 *   <li>Legendary bait (D8): not deducted on hooking; 1 only when a legendary species is actually
 *       caught. It stays if a normal species bites or the minigame fails
 *   <li>Forced move (ABORTED): returns 1 normal bait if one was deducted at hooking (legendary bait
 *       and saved bait were not deducted, so nothing to return)
 * </ul>
 */
public final class BaitRule {

    private BaitRule() {}

    /**
     * Whether it is deducted at the moment of hooking.
     *
     * @param saveRoll uniform random number in [0, 1)
     * @param saveChance bait_save_chance
     */
    public static boolean consumeOnHook(boolean legendBait, double saveRoll, double saveChance) {
        return !legendBait && saveRoll >= saveChance;
    }

    /** Whether it is deducted at the moment of the result (legendary bait only). */
    public static boolean consumeOnResult(boolean legendBait, MinigameResult result, Grade caught) {
        return legendBait && result == MinigameResult.CAUGHT && caught == Grade.LEGENDARY;
    }

    /**
     * Whether 1 is returned after the result: only a bait deducted at hooking, only on a forced
     * abort.
     */
    public static boolean refund(MinigameResult result, boolean consumedOnHook) {
        return consumedOnHook && result == MinigameResult.ABORTED;
    }
}
