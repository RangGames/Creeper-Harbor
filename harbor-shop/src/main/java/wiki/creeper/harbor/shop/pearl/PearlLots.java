package wiki.creeper.harbor.shop.pearl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pearl lot (pearl_lot) deduction order (docs 20 12.4 'paid vs free', tested without a server).
 * Free lots (pass, events) first, then paid lots from oldest. Proposed so that paid pearls eligible
 * for withdrawal stay longest; the order is shown in the shop terms and UI (legal review item).
 *
 * <p>Invariant: per player, remaining lot sum = pearl_wallet.balance, paid lot sum = paid_balance.
 */
public final class PearlLots {

    /** One lot. order is creation order (lot_id). */
    public record Lot(long id, boolean paid, long remaining) {}

    /** Amount to take from a lot. */
    public record Take(long lotId, boolean paid, long amount) {}

    private PearlLots() {}

    /**
     * Builds a deduction plan.
     *
     * @throws IllegalArgumentException when the remaining sum is insufficient
     */
    public static List<Take> allocate(List<Lot> lots, long amount) {
        if (amount <= 0) {
            return List.of();
        }
        List<Lot> order = new ArrayList<>(lots);
        order.sort(Comparator.comparing(Lot::paid).thenComparingLong(Lot::id));
        List<Take> takes = new ArrayList<>();
        long left = amount;
        for (Lot lot : order) {
            if (left == 0) {
                break;
            }
            if (lot.remaining() <= 0) {
                continue;
            }
            long take = Math.min(lot.remaining(), left);
            takes.add(new Take(lot.id(), lot.paid(), take));
            left -= take;
        }
        if (left > 0) {
            throw new IllegalArgumentException("묶음 잔량 부족: " + left);
        }
        return takes;
    }

    /** Amount of the plan taken from paid lots. */
    public static long paidPart(List<Take> takes) {
        return takes.stream().filter(Take::paid).mapToLong(Take::amount).sum();
    }
}
