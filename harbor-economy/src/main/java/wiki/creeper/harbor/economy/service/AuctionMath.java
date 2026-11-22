package wiki.creeper.harbor.economy.service;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.OptionalLong;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Auction math ("7. Balance design" 6.3, 6.5, docs 20 7.4 auction rules). No server API; unit
 * tested.
 *
 * <p>Example: listing at 10,000G → listing fee 100G, sale fee 500G on sale → seller nets 9,400G
 * (total cost 6% including the listing fee).
 */
public final class AuctionMath {

    private AuctionMath() {}

    /** Listing price range. */
    public record Bounds(long min, long max) {

        public boolean contains(long price) {
            return price >= min && price <= max;
        }
    }

    /**
     * Sale settlement. buyerPays = sellerGross = listing price, sellerNet = listing price − fee.
     *
     * @param fee sale fee (deducted from the seller as AUCTION_FEE)
     */
    public record Settlement(long buyerPays, long sellerGross, long fee, long sellerNet) {}

    /**
     * Listing price range = reference price × [min_ratio, max_ratio]. Lower bound rounded up, upper
     * bound rounded down, minimum 1G.
     */
    public static Bounds bounds(long ref, double minRatio, double maxRatio) {
        if (ref <= 0) {
            throw new IllegalArgumentException("기준가는 0보다 커야 합니다: " + ref);
        }
        long min = Math.max(1, (long) Math.ceil(ref * minRatio - 1e-9));
        long max = Math.max(min, (long) Math.floor(ref * maxRatio + 1e-9));
        return new Bounds(min, max);
    }

    /**
     * Species reference price: mythic uses the fixed reference price (D12, 6,000G); others use the
     * quality-adjusted NPC sell price (current M, no personal bonus, legendary cap included). No
     * NPC sell price (release_only, etc.) means it cannot be auctioned.
     */
    public static OptionalLong fishRef(Grade grade, OptionalLong npcPrice, long mythicRef) {
        if (grade == Grade.MYTHIC) {
            return OptionalLong.of(mythicRef);
        }
        if (npcPrice.isPresent() && npcPrice.getAsLong() > 0) {
            return npcPrice;
        }
        return OptionalLong.empty();
    }

    /**
     * Item reference price: sell_price → buy_price → auction_ref in that order (docs 20 7.4,
     * proposed).
     */
    public static OptionalLong itemRef(OptionalInt sell, OptionalInt buy, OptionalLong auctionRef) {
        if (sell.isPresent() && sell.getAsInt() > 0) {
            return OptionalLong.of(sell.getAsInt());
        }
        if (buy.isPresent() && buy.getAsInt() > 0) {
            return OptionalLong.of(buy.getAsInt());
        }
        if (auctionRef.isPresent() && auctionRef.getAsLong() > 0) {
            return auctionRef;
        }
        return OptionalLong.empty();
    }

    /** Listing fee = listing price × rate, rounded up (0 if the rate is 0). Not refundable. */
    public static long listFee(long price, double rate) {
        if (rate <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(price * rate - 1e-9));
    }

    /** Sale fee = sale price × rate, rounded down. */
    public static long saleFee(long price, double rate) {
        if (rate <= 0) {
            return 0;
        }
        return Math.min(price, (long) Math.floor(price * rate + 1e-9));
    }

    public static Settlement settle(long price, double saleFeeRate) {
        if (price <= 0) {
            throw new IllegalArgumentException("낙찰가는 0보다 커야 합니다: " + price);
        }
        long fee = saleFee(price, saleFeeRate);
        return new Settlement(price, price, fee, price - fee);
    }

    /**
     * Clamps the modified rate to [0, 1] (listing fee 1% − Auction Sense 0.5%/rank, sale fee 5% −
     * line passive).
     */
    public static double clampRate(double rate) {
        return Math.clamp(rate, 0.0, 1.0);
    }

    /** Listing slots = reputation tier slots + Auction Stall bonus (+2/rank). */
    public static int slots(int tierSlots, double bonus) {
        return Math.max(0, tierSlots + (int) Math.floor(bonus + 1e-9));
    }

    /** New accounts cannot list before n hours of play (buying is allowed). */
    public static boolean newAccountBlocked(Duration playtime, int hours) {
        return playtime.compareTo(Duration.ofHours(hours)) < 0;
    }
}
