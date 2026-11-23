package wiki.creeper.harbor.economy.api;

import java.util.List;
import java.util.OptionalLong;

/**
 * One keyset page of active listings ({@link AuctionService#activePage}).
 *
 * @param items listings, newest first
 * @param next cursor for the following page (the last item's id); empty on the last page
 */
public record AuctionPage(List<AuctionListingInfo> items, OptionalLong next) {

    /** Largest page size. */
    public static final int MAX_LIMIT = 200;

    public AuctionPage {
        items = List.copyOf(items);
    }
}
