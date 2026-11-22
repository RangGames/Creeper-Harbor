package wiki.creeper.harbor.shop.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;

/**
 * Pearl (paid currency) wallet (docs 20 12.2). Separate from Core CurrencyService, and deliberately
 * has no functions converting to gold, water scales or fish (economy separation, '6. BM plan' ch.
 * 1). Every change is recorded in the pearl_ledger with a unique reference ID, so the same
 * reference ID is processed only once.
 *
 * <p>Futures complete on the main thread after commit, at which point the balance cache and {@code
 * PearlBalanceChangedEvent} are updated. Paid pearls from payments can only be created by the Tebex
 * webhook ({@link #grant} in this API uses the free bucket).
 */
public interface PearlService {

    /**
     * Balance.
     *
     * @param paid paid-bucket balance (for withdrawal-right decisions)
     * @param debt shortfall after refunds or chargebacks (the balance never goes negative)
     */
    record Balance(long total, long paid, long debt) {

        public static final Balance EMPTY = new Balance(0, 0, 0);

        public long free() {
            return total - paid;
        }
    }

    /** Balance cache of an online player (main thread). {@link Balance#EMPTY} if not online. */
    Balance balance(UUID player);

    /** Reads from the DB (regardless of whether online). */
    CompletableFuture<Balance> balanceAsync(UUID player);

    /**
     * Spends pearls (free bucket first, then the oldest paid buckets). {@link LedgerError#REJECTED}
     * while shop-banned (shop_flags).
     *
     * @param refId reference ID (order ID etc., 1–64 chars)
     */
    CompletableFuture<Result<Long, LedgerError>> spend(
            UUID player, long amount, LedgerReason reason, String refId);

    /** Grants from the free bucket (pass rewards, events, admins). */
    CompletableFuture<Result<Long, LedgerError>> grant(
            UUID player, long amount, LedgerReason reason, String refId);

    /** Whether shop-banned (online cache, main thread). */
    boolean locked(UUID player);
}
