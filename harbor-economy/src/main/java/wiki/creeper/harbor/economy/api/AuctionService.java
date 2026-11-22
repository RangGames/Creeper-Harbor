package wiki.creeper.harbor.economy.api;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Auction house queries (docs 20 7.2, read-only). Listing and buying happen only in the player's
 * /경매 screen. Result futures complete on the main thread.
 */
public interface AuctionService {

    /** Active listings (newest first). A null category means all. */
    CompletableFuture<List<AuctionListingInfo>> active(
            @org.jspecify.annotations.Nullable String category, int limit);

    /** Lowest active price for this item (species) ID. */
    CompletableFuture<OptionalLong> lowestPrice(String itemId);

    /** Number of active listings by the seller. */
    CompletableFuture<Integer> activeCount(UUID seller);
}
