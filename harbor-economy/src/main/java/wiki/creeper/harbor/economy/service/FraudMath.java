package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/**
 * Fraudulent-trade score (docs 20 7.4 fraud prevention, all numbers are proposed values). No server
 * API; unit tested.
 *
 * <ul>
 *   <li>linked: trade between accounts linked by a non-shared IP (Common AccountLinkReader)
 *   <li>pair_repeat: 3 or more trades between the same two people within 7 days (including this
 *       one)
 *   <li>price_outlier: sale at 5× the reference price or more
 *   <li>wash: trade between linked accounts at 0.6× the reference price or less (dumping)
 *   <li>trap_alt: trade of trap species between linked accounts ("12. World · Housing · Traps" ch.
 *       5)
 * </ul>
 */
public final class FraudMath {

    private FraudMath() {}

    /**
     * Observations for one trade.
     *
     * @param pairTrades number of trades between the same two people within the window (excluding
     *     this one)
     * @param priceRatio sale price ÷ reference price
     * @param trapSource whether it is a trap harvest
     */
    public record Signals(boolean linked, int pairTrades, double priceRatio, boolean trapSource) {}

    /**
     * Verdict.
     *
     * @param reasons rule codes that matched (used in trade_flag.kind and detail)
     * @param flag subject to trade_flag recording and admin alert
     * @param hold subject to withholding the sale proceeds
     */
    public record Verdict(double score, List<String> reasons, boolean flag, boolean hold) {

        /** trade_flag.kind: the first rule that matched (none if none). */
        public String kind() {
            return reasons.isEmpty() ? "none" : reasons.getFirst();
        }
    }

    public static Verdict evaluate(Signals s, EconomyBalance.FraudRules rules) {
        double score = 0;
        List<String> reasons = new ArrayList<>();
        if (s.linked()) {
            score += rules.linkedScore();
            reasons.add("linked");
        }
        if (s.pairTrades() + 1 >= rules.pairCount()) {
            score += rules.pairScore();
            reasons.add("pair_repeat");
        }
        if (s.priceRatio() >= rules.outlierRatio()) {
            score += rules.outlierScore();
            reasons.add("price_outlier");
        }
        if (s.linked() && s.priceRatio() <= rules.washRatio()) {
            score += rules.washScore();
            reasons.add("wash");
        }
        if (s.linked() && s.trapSource()) {
            score += rules.trapAltScore();
            reasons.add("trap_alt");
        }
        return new Verdict(
                score,
                List.copyOf(reasons),
                score >= rules.flagScore(),
                score >= rules.holdScore());
    }

    /**
     * Auction market index = median of sale price / reference price ratios ("7. Balance design" ch.
     * 11: auction rare median price / NPC price, target 0.8–2.0). NaN if there are no samples.
     */
    public static double index(List<Double> ratios) {
        if (ratios.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = ratios.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
