package wiki.creeper.harbor.economy.content;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Quality;

/**
 * The balance.yml economy section (docs 20 7.3). Missing values fall back to "7. Balance design"
 * ch. 6 or proposed values.
 */
public record EconomyBalance(Balance balance) {

    /** Quality multipliers (D2 final values 1.0 / 1.2 / 1.5 / 2.5 / 5.0, docs 20 0.6). */
    public double qualityMult(Quality quality) {
        double fallback =
                switch (quality.stars()) {
                    case 1 -> 1.0;
                    case 2 -> 1.2;
                    case 3 -> 1.5;
                    case 4 -> 2.5;
                    default -> 5.0;
                };
        return balance.number("economy.quality_mult." + quality.stars(), fallback);
    }

    /** Region factors (Void Sea 0.65). Keyed by the last segment of the region ID. */
    public double regionFactor(String region) {
        return balance.number("economy.region_factor." + region, 1.0);
    }

    public long legendCap() {
        return (long) balance.number("economy.legend_cap", 3000);
    }

    public double marketK() {
        return balance.number("economy.market.slope", 0.35);
    }

    public double marketMin() {
        return balance.number("economy.market.min", 0.5);
    }

    public double marketMax() {
        return balance.number("economy.market.max", 1.2);
    }

    public int marketWindowHours() {
        return balance.integer("economy.market.window_h", 24);
    }

    public double s0Floor() {
        return balance.number("economy.market.s0_floor", 20);
    }

    public double catchesPerHour() {
        return balance.number("economy.market.catches_per_hour", 80);
    }

    public double sellRatio() {
        return balance.number("economy.market.sell_ratio", 0.85);
    }

    public double repairRate() {
        return balance.number("economy.repair.rate", 0.03);
    }

    public double repairBrokenRate() {
        return balance.number("economy.repair.broken_rate", 0.10);
    }

    // ------------------------------------------------------------------ Market (added)

    /**
     * Bulk-sale slippage threshold (applies when selling more than this of one species at once,
     * proposed value 32).
     */
    public int bulkThreshold() {
        return balance.integer("economy.bulk.threshold", 32);
    }

    /** Alert admins if the market has not updated for longer than this (docs 20 7.4, 3 hours). */
    public int marketStaleHours() {
        return balance.integer("economy.market.stale_h", 3);
    }

    /** Maximum fish count for a bulk-buyer trade (docs 20 7.4, 64). */
    public int bigDealMax() {
        return balance.integer("economy.big_deal.max", 64);
    }

    /** Fixed market factor for bulk-buyer trades (1.0). */
    public double bigDealCoef() {
        return balance.number("economy.big_deal.coef", 1.0);
    }

    // ------------------------------------------------------------------ Auction ("7. Balance
    // design" 6.3, 6.5)

    public double auctionListFee() {
        return balance.number("economy.auction.list_fee", 0.01);
    }

    public double auctionSaleFee() {
        return balance.number("economy.auction.sale_fee", 0.05);
    }

    public double auctionMinRatio() {
        return balance.number("economy.auction.min_ratio", 0.5);
    }

    public double auctionMaxRatio() {
        return balance.number("economy.auction.max_ratio", 10);
    }

    public int auctionDurationHours() {
        return balance.integer("economy.auction.duration_h", 48);
    }

    /** Listing slots per reputation tier (5 / 10 / 15; tier mapping is a proposed value). */
    public int auctionSlots(int reputationTier) {
        int fallback = reputationTier >= 5 ? 15 : reputationTier >= 3 ? 10 : 5;
        return balance.integer("economy.auction.slots." + reputationTier, fallback);
    }

    public int auctionNewAccountHours() {
        return balance.integer("economy.auction.new_account_h", 5);
    }

    /** Auction reference price for mythic species (D12, 6,000G). */
    public long auctionMythicRef() {
        return (long) balance.number("economy.auction.mythic_ref", 6000);
    }

    /** Radius where listing and cancelling are allowed (from the auction board, 8 blocks). */
    public double auctionBoardRadius() {
        return balance.number("economy.auction.board_radius", 8);
    }

    /** First-page cache per category (30 seconds). */
    public int auctionCacheSeconds() {
        return balance.integer("economy.auction.cache_s", 30);
    }

    // ------------------------------------------------------------------ Crafting

    /** Station radius that /제작 opens (proposed value 6). */
    public double craftStationRadius() {
        return balance.number("economy.craft.station_radius", 6);
    }

    /**
     * Material saving cap (proposed value 0.5; no matter how large the saving modifier, half the
     * materials are still used).
     */
    public double craftMaxSave() {
        return balance.number("economy.craft.max_save", 0.5);
    }

    // ------------------------------------------------------------------ Fraudulent-trade
    // monitoring (docs 20
    // 7.4, all proposed values)

    public FraudRules fraud() {
        return new FraudRules(
                balance.number("economy.fraud.linked_score", 60),
                balance.integer("economy.fraud.pair_window_d", 7),
                balance.integer("economy.fraud.pair_count", 3),
                balance.number("economy.fraud.pair_score", 40),
                balance.number("economy.fraud.outlier_ratio", 5),
                balance.number("economy.fraud.outlier_score", 30),
                balance.number("economy.fraud.wash_ratio", 0.6),
                balance.number("economy.fraud.wash_score", 20),
                balance.number("economy.fraud.trap_alt_score", 30),
                balance.number("economy.fraud.flag_score", 30),
                balance.number("economy.fraud.hold_score", 70),
                balance.integer("economy.fraud.hold_h", 24),
                balance.bool("economy.fraud.auto_release", true),
                balance.number("economy.fraud.index_min", 0.8),
                balance.number("economy.fraud.index_max", 2.0));
    }

    /**
     * Fraudulent-trade scoring rules.
     *
     * @param linkedScore trade between two accounts linked by the same IP (shared IPs excluded)
     * @param pairCount if trades between the same two people within the window reach this count
     *     (including this one)
     * @param outlierRatio sale at this multiple of the reference price or more
     * @param washRatio trade between linked accounts at this multiple of the reference price or
     *     less (dumping)
     * @param trapAltScore trade of trap species between linked accounts ("12. World · Housing ·
     *     Traps" ch. 5)
     * @param flagScore at or above this score, record trade_flag and alert admins
     * @param holdScore at or above this score, withhold the sale proceeds
     * @param autoRelease auto-release once the hold time passes (if false, held until an admin
     *     releases it; docs 20 7.8 open question)
     * @param indexMin lower bound for the auction rare market index warning ("7. Balance design"
     *     ch. 11)
     */
    public record FraudRules(
            double linkedScore,
            int pairWindowDays,
            int pairCount,
            double pairScore,
            double outlierRatio,
            double outlierScore,
            double washRatio,
            double washScore,
            double trapAltScore,
            double flagScore,
            double holdScore,
            int holdHours,
            boolean autoRelease,
            double indexMin,
            double indexMax) {}
}
