package wiki.creeper.harbor.economy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 경매 등록 한 건 (조회용).
 *
 * @param itemId 어종 ID 또는 아이템 ID ({@code harbor:fish/…}, {@code harbor:item/…})
 * @param category fish · gear · bait · material · food · other
 * @param price 묶음 전체 가격
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
        Instant expiresAt) {}
