package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;

/**
 * Grants and deducts gold and sea scales (물비늘). Each change is an immediate transaction recorded in
 * the ledger. After commit, the cached balance is updated on the main thread and
 * CurrencyChangedEvent is fired; the returned future also completes on the main thread.
 *
 * <p>Rules such as shops, auctions and market prices live in the Economy module.
 */
public interface CurrencyService {

    /**
     * @return balance after the operation
     */
    CompletableFuture<Result<Long, LedgerError>> give(
            UUID player,
            Currency currency,
            long amount,
            LedgerReason reason,
            @Nullable String refId);

    CompletableFuture<Result<Long, LedgerError>> take(
            UUID player,
            Currency currency,
            long amount,
            LedgerReason reason,
            @Nullable String refId);

    /**
     * Applies changes across several players and currencies plus extra SQL in one transaction
     * (auction settlement: buyer debit + seller credit + fee + auction row state change). If any
     * part fails, everything is rolled back. Does not handle pearls (PEARL).
     *
     * @param extra extra SQL to run in the same transaction (null = none)
     * @return balances after the operation, in input order
     */
    CompletableFuture<Result<List<Long>, LedgerError>> atomic(
            List<LedgerEntry> entries, @Nullable SqlConsumer extra);

    /**
     * Balance for the current season. The cached value if the player is on this server, otherwise
     * read from the DB (players on other servers and offline included; 0 if no row). Completes on
     * the main thread.
     */
    CompletableFuture<Long> balance(UUID player, Currency currency);

    /**
     * Balance ranking for the current season (only players with a balance above 0, ties by name).
     * The top {@value #TOP_CACHED} are cached for 30 seconds, so they may lag slightly. Completes
     * on the main thread.
     *
     * @param offset number of rows to skip (0 = from first place)
     * @param limit maximum number of rows (1~100)
     */
    CompletableFuture<List<BalanceRank>> top(Currency currency, int offset, int limit);

    /**
     * Recent ledger records (newest first, proposed: player transaction history screen). Not
     * limited to a season. Completes on the main thread.
     *
     * @param limit maximum number of rows (1~200)
     */
    CompletableFuture<List<LedgerRecord>> history(UUID player, Currency currency, int limit);

    /** Size of the top cache. */
    int TOP_CACHED = 100;

    /**
     * Rank for the current season (starting at 1). Empty if the balance is 0 or there is no row.
     * Completes on the main thread.
     */
    CompletableFuture<Optional<Integer>> rank(UUID player, Currency currency);

    /**
     * Sets the balance to amount (admin command). Records the difference from the current balance
     * in the ledger as ADMIN_GRANT or ADMIN_TAKE. If another change slips in between reading and
     * writing the balance, the result can be off by that much (operators only; returns the balance
     * after the operation).
     *
     * @return balance after the operation; the current balance if there was no difference
     */
    CompletableFuture<Result<Long, LedgerError>> set(
            UUID player, Currency currency, long amount, @Nullable String refId);

    /**
     * Transfers in one transaction (auction settlement, etc.). If any part fails, everything is
     * rolled back.
     *
     * @return [sender balance, receiver balance]
     */
    CompletableFuture<Result<List<Long>, LedgerError>> transfer(
            UUID from,
            UUID to,
            Currency currency,
            long amount,
            LedgerReason debitReason,
            LedgerReason creditReason,
            String refId);
}
