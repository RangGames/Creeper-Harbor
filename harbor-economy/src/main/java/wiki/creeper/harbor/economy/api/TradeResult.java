package wiki.creeper.harbor.economy.api;

import java.util.Locale;

/**
 * Result of a trade done for a player through the api (sell, shop buy, auction listing and
 * purchase, craft, repair). The trade runs the same flow as the player's own screen (TradeGuard
 * marker, one ledger transaction, items held and returned on failure) without the confirmation
 * dialog; the player still gets the usual chat notices.
 *
 * @param gold gold moved: sale total, purchase price, listing fee, craft cost or repair cost (0 if
 *     nothing happened)
 * @param amount items involved (fish sold, items bought or crafted, listed stack size)
 * @param id auction listing ID for listings and auction purchases, otherwise 0
 */
public record TradeResult(Status status, long gold, int amount, long id) {

    /** Outcome. */
    public enum Status {
        /** Done (committed). */
        OK,
        /** Nothing to trade (no fish to sell, empty hand, nothing to repair). */
        NOTHING,
        /** Unknown shop, shop entry, recipe or listing. */
        UNKNOWN,
        /** Not unlocked yet (job tier, level, flags, quest). */
        LOCKED,
        /** Another trade of this player is in progress, or a server move is under way. */
        BUSY,
        /** Not enough gold. */
        NO_GOLD,
        /** No free inventory slot (shop purchases need one). */
        INVENTORY_FULL,
        /** Daily or weekly limit reached. */
        LIMIT,
        /** Missing crafting materials. */
        MISSING,
        /** The item cannot be traded (locked item, not tradeable, mythic or release-only fish). */
        NOT_TRADEABLE,
        /** Listing price outside the allowed range. */
        PRICE_RANGE,
        /** New account auction block. */
        NEW_ACCOUNT,
        /** Not within an auction board's radius. */
        NOT_NEAR,
        /** All auction slots are in use. */
        SLOTS_FULL,
        /** The auction already ended or its price changed. */
        GONE,
        /** Own auction listing. */
        OWN,
        /** Rejected or a database error; items were returned. */
        FAILED;

        /** Lowercase key (for scripts and logs). */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static TradeResult of(Status status) {
        return new TradeResult(status, 0, 0, 0);
    }

    public static TradeResult ok(long gold, int amount) {
        return new TradeResult(Status.OK, gold, amount, 0);
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
