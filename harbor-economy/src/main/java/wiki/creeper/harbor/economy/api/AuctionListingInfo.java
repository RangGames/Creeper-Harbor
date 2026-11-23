package wiki.creeper.harbor.economy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A single auction listing (for queries).
 *
 * @param itemId fish species ID or item ID ({@code harbor:fish/…}, {@code harbor:item/…})
 * @param category fish · gear · bait · material · food · other
 * @param price price for the whole stack
 * @param createdAt when it was listed
 */
public record AuctionListingInfo(
        long id,
        UUID seller,
        String sellerName,
        String itemId,
        String itemName,
        String category,
        int amount,
        long price,
        Instant expiresAt,
        Instant createdAt) {}
