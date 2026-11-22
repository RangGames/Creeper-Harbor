package wiki.creeper.harbor.shop.pass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.shop.content.PassDef;

/**
 * Season pass math (tested without a server). Level = min(max level, cumulative exp ÷ exp per level
 * + bought levels). Only cumulative exp is stored and it never decreases, so late write-behind
 * saves can be merged with {@code GREATEST}. Bought levels (bought) are changed only in immediate
 * transactions.
 */
public final class PassMath {

    /** Premium tier (season_pass.premium). */
    public static final int FREE = 0;

    public static final int PREMIUM = 1;
    public static final int PREMIUM_PLUS = 2;

    private PassMath() {}

    /** Current level from cumulative exp and bought levels (0 = before level 1). */
    public static int level(PassDef pass, long exp, int bought) {
        long byExp = Math.max(0, exp) / pass.expPerLevel();
        return (int) Math.min(pass.levels(), byExp + Math.max(0, bought));
    }

    /**
     * Progress toward the next level (0 or more, less than expPerLevel). expPerLevel at max level.
     */
    public static long progress(PassDef pass, long exp, int bought) {
        if (level(pass, exp, bought) >= pass.levels()) {
            return pass.expPerLevel();
        }
        return Math.max(0, exp) % pass.expPerLevel();
    }

    /** Number of levels that can be bought up to the max level. */
    public static int buyableLevels(PassDef pass, long exp, int bought) {
        return pass.levels() - level(pass, exp, bought);
    }

    /** Price of buying n levels. -1 if it would exceed the max level. */
    public static long levelPrice(PassDef pass, long exp, int bought, int count) {
        if (count <= 0 || count > buyableLevels(pass, exp, bought)) {
            return -1;
        }
        return (long) count * pass.prices().level();
    }

    /**
     * Price of raising the premium tier. 0→1 premium, 0→2 premium_plus, 1→2 upgrade. -1 if lowering
     * or equal.
     */
    public static long premiumPrice(PassDef pass, int from, int to) {
        if (to <= from || from < FREE || to > PREMIUM_PLUS) {
            return -1;
        }
        if (from == FREE) {
            return to == PREMIUM ? pass.prices().premium() : pass.prices().premiumPlus();
        }
        return pass.prices().upgrade();
    }

    /** Levels granted instantly when upgrading to premium+ (0 if already premium+). */
    public static int premiumPlusBonus(PassDef pass, int from, int to) {
        return from < PREMIUM_PLUS && to == PREMIUM_PLUS ? pass.premiumPlusLevels() : 0;
    }

    /** Catch exp source key (catch.&lt;grade&gt;). */
    public static String catchSource(Grade grade) {
        return "catch." + grade.key();
    }

    /**
     * Result of adding exp. Exp keeps accumulating past the max level (cumulative, for display).
     */
    public static long addExp(long exp, long amount) {
        return amount <= 0 ? exp : Math.addExact(exp, amount);
    }

    /**
     * Rewards available but not yet claimed. Free track as soon as the level is reached; for
     * premium bought late, all already-reached levels at once.
     *
     * @param claimed already-claimed reward keys (level + ":" + claimKey)
     */
    public static List<PassDef.Reward> claimable(
            PassDef pass, int level, int premium, Set<String> claimed) {
        List<PassDef.Reward> result = new ArrayList<>();
        for (PassDef.Reward reward : pass.rewards()) {
            if (reward.level() <= level
                    && reward.track().openAt(premium)
                    && !claimed.contains(claimKey(reward))) {
                result.add(reward);
            }
        }
        return result;
    }

    /** Key identifying a claimed reward (level + track columns of season_pass_claim). */
    public static String claimKey(PassDef.Reward reward) {
        return reward.level() + ":" + reward.claimKey();
    }

    /** Total pearl rewards per track (for checking free 150 · premium 1,000). */
    public static int pearlTotal(PassDef pass, PassDef.Track track) {
        return pass.rewards().stream()
                .filter(r -> r.track() == track)
                .mapToInt(PassDef.Reward::pearls)
                .sum();
    }
}
