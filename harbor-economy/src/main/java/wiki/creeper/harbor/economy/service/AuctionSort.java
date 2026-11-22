package wiki.creeper.harbor.economy.service;

import java.util.Locale;

/**
 * Auction list sort order (proposed). Pressing the button cycles to the next order. SQL ORDER BY is
 * fixed text, so no input is mixed in.
 */
public enum AuctionSort {
    LATEST("created_at DESC, id DESC"),
    PRICE_ASC("price ASC, created_at DESC"),
    PRICE_DESC("price DESC, created_at DESC"),
    ENDING("expires_at ASC, id ASC"),
    /** Highest grade first, then cheapest. Unknown grades last (FIELD = 0). */
    GRADE(
            "FIELD(grade, 'common', 'uncommon', 'rare', 'epic', 'legendary', 'mythic') DESC,"
                    + " price ASC");

    private final String orderBy;

    AuctionSort(String orderBy) {
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    /** messages_ko.yml key name. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public AuctionSort next() {
        return switch (this) {
            case LATEST -> PRICE_ASC;
            case PRICE_ASC -> PRICE_DESC;
            case PRICE_DESC -> ENDING;
            case ENDING -> GRADE;
            case GRADE -> LATEST;
        };
    }
}
