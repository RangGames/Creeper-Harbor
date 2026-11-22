package wiki.creeper.harbor.shop.pearl;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.api.event.PearlBalanceChangedEvent;
import wiki.creeper.harbor.shop.data.ShopRepository;

/**
 * Pearl wallet (PearlService implementation, docs 20 12.4 'pearl ledger'). Every pearl sink (shop,
 * pass, chest, gift, nickname) uses {@link #transact} to put order record, pearl deduction and
 * ownership record in one transaction. The cache and PearlBalanceChangedEvent are updated only
 * after commit.
 */
public final class PearlManager implements PearlService {

    /** Failure reason and the message key to show. */
    public record Failure(LedgerError error, String messageKey) {}

    /** Transaction work. */
    @FunctionalInterface
    public interface Work<T> {
        T run(PearlTx tx) throws SQLException;
    }

    private final ShopRuntime rt;

    public PearlManager(ShopRuntime rt) {
        this.rt = rt;
    }

    @Override
    public Balance balance(UUID player) {
        return rt.profile(player).wallet();
    }

    @Override
    public CompletableFuture<Balance> balanceAsync(UUID player) {
        return rt.db(c -> ShopRepository.wallet(c, player));
    }

    @Override
    public boolean locked(UUID player) {
        return rt.profile(player).locked();
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> spend(
            UUID player, long amount, LedgerReason reason, String refId) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(Result.err(LedgerError.REJECTED));
        }
        return transact(
                        tx -> {
                            tx.requireUnlocked(player);
                            return tx.apply(PearlLedger.Change.debit(player, amount, reason, refId))
                                    .balance();
                        })
                .thenApply(r -> r.mapError());
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> grant(
            UUID player, long amount, LedgerReason reason, String refId) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(Result.err(LedgerError.REJECTED));
        }
        return transact(
                        tx ->
                                tx.apply(
                                                PearlLedger.Change.credit(
                                                        player, amount, reason, refId, false))
                                        .balance())
                .thenApply(r -> r.mapError());
    }

    /** Admin take (ADMIN_TAKE). Processed even while the shop ban is active. */
    public CompletableFuture<Result<Long, LedgerError>> spendAdmin(
            UUID player, long amount, String refId) {
        return transact(
                        tx ->
                                tx.apply(
                                                PearlLedger.Change.debit(
                                                        player,
                                                        amount,
                                                        LedgerReason.ADMIN_TAKE,
                                                        refId))
                                        .balance())
                .thenApply(r -> r.mapError());
    }

    /** Result wrapper (helper converting Failure → LedgerError). */
    public record Outcome<T>(Result<T, Failure> result) {

        public Result<T, LedgerError> mapError() {
            return switch (result) {
                case Result.Ok<T, Failure>(T value) -> Result.ok(value);
                case Result.Err<T, Failure>(Failure f) -> Result.err(f.error());
            };
        }
    }

    /**
     * Runs as one transaction on a virtual thread; after commit, updates the changed wallet cache
     * and events on the main thread, then returns the result. {@link ShopReject} and {@link
     * LedgerException} are rolled back and returned as failures.
     */
    public <T> CompletableFuture<Outcome<T>> transact(Work<T> work) {
        return rt.api()
                .database()
                .async(
                        c -> {
                            PearlTx tx = new PearlTx(c, rt.ledger());
                            T value = work.run(tx);
                            return new Committed<>(
                                    value, List.copyOf(tx.applied()), List.copyOf(tx.changes()));
                        })
                .handleAsync(
                        (committed, error) -> {
                            if (error != null) {
                                return new Outcome<T>(Result.err(failure(error)));
                            }
                            afterCommit(committed.applied(), committed.changes());
                            return new Outcome<T>(Result.ok(committed.value()));
                        },
                        rt.tasks().main());
    }

    /**
     * Committed transaction result. After commit, {@link #afterCommit(Committed)} syncs the cache.
     */
    public record Committed<T>(
            T value, List<PearlLedger.Applied> applied, List<PearlLedger.Change> changes) {}

    /**
     * Runs as one transaction on the current thread (virtual threads only: webhook processor,
     * season rollover hook). The caller syncs the cache on the main thread with {@link
     * #afterCommit(Committed)}.
     */
    public <T> Committed<T> runBlocking(Work<T> work) throws SQLException {
        return rt.api()
                .database()
                .tx(
                        c -> {
                            PearlTx tx = new PearlTx(c, rt.ledger());
                            T value = work.run(tx);
                            return new Committed<>(
                                    value, List.copyOf(tx.applied()), List.copyOf(tx.changes()));
                        });
    }

    public void afterCommit(Committed<?> committed) {
        afterCommit(committed.applied(), committed.changes());
    }

    /**
     * Applies committed pearl changes to the cache (main thread). Also called by code that ran its
     * own transaction, such as webhook processing.
     */
    public void afterCommit(List<PearlLedger.Applied> applied, List<PearlLedger.Change> changes) {
        Map<UUID, PearlLedger.Applied> last = new LinkedHashMap<>();
        Map<UUID, Long> delta = new LinkedHashMap<>();
        Map<UUID, LedgerReason> reason = new LinkedHashMap<>();
        for (int i = 0; i < applied.size(); i++) {
            PearlLedger.Applied a = applied.get(i);
            last.put(a.player(), a);
            delta.merge(a.player(), changes.get(i).delta(), Long::sum);
            reason.put(a.player(), changes.get(i).reason());
        }
        last.forEach(
                (player, a) -> {
                    rt.update(
                            player,
                            p ->
                                    p.withWallet(
                                            new Balance(
                                                    a.balance(),
                                                    a.paidBalance(),
                                                    p.wallet().debt())));
                    if (rt.online(player).isPresent()) {
                        rt.call(
                                new PearlBalanceChangedEvent(
                                        player,
                                        a.balance(),
                                        a.paidBalance(),
                                        delta.getOrDefault(player, 0L),
                                        reason.getOrDefault(player, LedgerReason.SHOP_BUY)));
                    }
                });
    }

    private record WalletRow(Balance balance, boolean locked) {}

    /**
     * Re-reads wallet and ban state from the DB into the cache (payments and refunds processed on
     * another server).
     */
    public CompletableFuture<Void> refresh(UUID player) {
        return rt.db(
                        c ->
                                new WalletRow(
                                        ShopRepository.wallet(c, player),
                                        ShopRepository.locked(c, player)))
                .thenAccept(
                        row -> {
                            Balance before = balance(player);
                            Balance after = row.balance();
                            rt.update(player, p -> p.withWallet(after).withLocked(row.locked()));
                            if (before.total() != after.total() && rt.online(player).isPresent()) {
                                rt.call(
                                        new PearlBalanceChangedEvent(
                                                player,
                                                after.total(),
                                                after.paid(),
                                                after.total() - before.total(),
                                                LedgerReason.TOPUP));
                            }
                        });
    }

    static Failure failure(Throwable error) {
        Throwable cause =
                error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
        if (cause instanceof ShopReject reject) {
            return new Failure(LedgerError.REJECTED, reject.messageKey());
        }
        if (cause instanceof LedgerException le) {
            return new Failure(
                    le.error(),
                    switch (le.error()) {
                        case INSUFFICIENT_FUNDS -> "shop.error.no_pearls";
                        case DUPLICATE -> "shop.error.duplicate";
                        case REJECTED -> "shop.error.rejected";
                        default -> "shop.error.failed";
                    });
        }
        System.getLogger("Harbor/Shop").log(System.Logger.Level.ERROR, "진주 트랜잭션 실패", cause);
        return new Failure(LedgerError.DATABASE, "shop.error.failed");
    }
}
