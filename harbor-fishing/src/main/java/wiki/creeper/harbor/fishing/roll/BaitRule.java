package wiki.creeper.harbor.fishing.roll;

import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.MinigameResult;

/**
 * Bait consumption rules (docs 20 0.4, 3.4 "bait · gear consumption"). No server API (unit tests).
 *
 * <ul>
 *   <li>Normal bait: 1 at the moment of hooking (HOOKED). Consumed on success or failure; reeling
 *       early or missing the bite consumes none. On success, 1 is returned by bait_save_chance
 *   <li>Legendary bait (D8): not deducted on hooking; 1 only when a legendary species is actually
 *       caught. It stays if a normal species bites or the minigame fails
 *   <li>Forced move (ABORTED): returns 1 normal bait (legendary bait was not deducted, so nothing
 *       to return)
 * </ul>
 */
public final class BaitRule {

    private BaitRule() {}

    /** Whether it is deducted at the moment of hooking. */
    public static boolean consumeOnHook(boolean legendBait) {
        return !legendBait;
    }

    /** Whether it is deducted at the moment of the result (legendary bait only). */
    public static boolean consumeOnResult(boolean legendBait, MinigameResult result, Grade caught) {
        return legendBait && result == MinigameResult.CAUGHT && caught == Grade.LEGENDARY;
    }

    /**
     * Whether 1 is returned after the result. saveRoll is a uniform random number, saveChance is
     * bait_save_chance.
     */
    public static boolean refund(
            boolean legendBait, MinigameResult result, double saveRoll, double saveChance) {
        if (legendBait) {
            return false;
        }
        return switch (result) {
            case CAUGHT -> saveRoll < saveChance;
            case ABORTED -> true;
            case LINE_BROKE, ESCAPED -> false;
        };
    }
}
