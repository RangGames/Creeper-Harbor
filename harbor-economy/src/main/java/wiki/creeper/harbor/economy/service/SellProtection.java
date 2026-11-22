package wiki.creeper.harbor.economy.service;

/**
 * Valuable fish protection rules (proposed, no server API). "Add all" and /판매 sell-all skip
 * protected fish, and protected fish placed directly in the sell screen are sold only after a
 * separate confirmation. Locked items ({@link ItemLock}) also cannot be auctioned.
 */
public final class SellProtection {

    /** Protection reason (in priority order). */
    public enum Reason {
        NONE,
        /** Item locked by the player. */
        LOCKED,
        /** Species with a unique number (★5, legendary, mythic; fish_ledger). */
        UNIQUE,
        /** Top quality (★5). */
        MAX_QUALITY;

        public boolean protects() {
            return this != NONE;
        }
    }

    private SellProtection() {}

    /**
     * @param stars number of quality stars
     * @param unique whether it has a unique number
     * @param locked whether it has the lock marker
     */
    public static Reason of(int stars, int maxStars, boolean unique, boolean locked) {
        if (locked) {
            return Reason.LOCKED;
        }
        if (unique) {
            return Reason.UNIQUE;
        }
        if (stars >= maxStars) {
            return Reason.MAX_QUALITY;
        }
        return Reason.NONE;
    }
}
