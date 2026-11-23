package wiki.creeper.harbor.economy.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * Auction house (docs 20 7.2): queries, plus listing and buying for a player through the same flows
 * as the /경매 screen. Result futures complete on the main thread.
 */
public interface AuctionService {

    /** Active listings (newest first). A null category means all. */
    CompletableFuture<List<AuctionListingInfo>> active(
            @org.jspecify.annotations.Nullable String category, int limit);

    /**
     * Read-only keyset page of active, unexpired listings, newest first (id descending), read from
     * the DB without the screen cache (for external readers such as the Harbor-Web REST API).
     *
     * @param category fish · gear · bait · material · food · other, or null/"all" for every
     *     category
     * @param cursor the previous page's {@link AuctionPage#next()} (only listings with a smaller id
     *     follow); 0 for the first page
     * @param limit page size, clamped to 1 ~ {@link AuctionPage#MAX_LIMIT}
     */
    CompletableFuture<AuctionPage> activePage(
            @org.jspecify.annotations.Nullable String category, long cursor, int limit);

    /** Lowest active price for this item (species) ID. */
    CompletableFuture<OptionalLong> lowestPrice(String itemId);

    /** Number of active listings by the seller. */
    CompletableFuture<Integer> activeCount(UUID seller);

    /** One active listing by ID (empty if it ended, expired or does not exist). */
    CompletableFuture<Optional<AuctionListingInfo>> listing(long listingId);

    /**
     * Lists the held stack for the player at this price, the same checks and transaction as /경매 등록
     * (auction board radius, new account, tradeable, price range, slots, listing fee, unique fish
     * ledger) without the reference price screen. Call on the main thread.
     *
     * @return id = new listing ID and gold = listing fee on success
     */
    CompletableFuture<TradeResult> listHeld(Player player, long price);

    /**
     * Buys a listing for the player, the same settlement as clicking it in /경매 (one transaction:
     * buyer, seller, fee, auction row, suspicion check; the item arrives by mail and is claimed
     * automatically). Call on the main thread.
     *
     * @return gold = price paid and id = listing ID on success
     */
    CompletableFuture<TradeResult> buy(Player player, long listingId);
}
