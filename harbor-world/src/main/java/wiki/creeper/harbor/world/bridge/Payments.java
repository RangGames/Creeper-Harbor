package wiki.creeper.harbor.world.bridge;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.world.WorldKit;

/**
 * Gold payments (ships, upgrades, decor, repairs). With Economy it uses ShopService.charge and
 * RepairService (marks the trade as in progress to delay server moves); without it, Core
 * CurrencyService.atomic applies the ledger entry and the World table update in one transaction
 * (docs 20 8.1 'gold deduction rules belong to Economy'). Call on the main thread; the future
 * completes on the main thread.
 */
public interface Payments {

    /**
     * @param extra SQL to run in the same transaction. Throws {@link LedgerException} (REJECTED) on
     *     a business rule violation
     */
    CompletableFuture<Result<Long, LedgerError>> charge(
            Player player,
            long price,
            LedgerReason reason,
            String refId,
            @Nullable SqlConsumer extra);

    /** Repair cost quote (Economy RepairService: price × ratio × repair_cost modifier). */
    long repairQuote(UUID player, long price, boolean broken);

    /** Repair payment (reason REPAIR). */
    CompletableFuture<Result<Long, LedgerError>> repair(
            Player player, long cost, String refId, @Nullable SqlConsumer extra);

    /** Message key for a failed payment. */
    static String failKey(Result<Long, LedgerError> result) {
        if (result instanceof Result.Err<Long, LedgerError>(LedgerError error)) {
            return switch (error) {
                case INSUFFICIENT_FUNDS -> "world.pay.no_gold";
                case DUPLICATE, REJECTED -> "world.pay.rejected";
                default -> "world.pay.failed";
            };
        }
        return "world.pay.failed";
    }

    static Payments create(WorldKit kit) {
        if (Bukkit.getPluginManager().getPlugin("Harbor-Economy") != null) {
            return new EconomyPayments(kit, new CorePayments(kit));
        }
        return new CorePayments(kit);
    }

    /** Payments using Core only. */
    final class CorePayments implements Payments {

        private final WorldKit kit;

        CorePayments(WorldKit kit) {
            this.kit = kit;
        }

        @Override
        public CompletableFuture<Result<Long, LedgerError>> charge(
                Player player,
                long price,
                LedgerReason reason,
                String refId,
                @Nullable SqlConsumer extra) {
            UUID uuid = player.getUniqueId();
            if (price <= 0) {
                return free(uuid, extra);
            }
            return kit.api()
                    .currency()
                    .atomic(
                            List.of(new LedgerEntry(uuid, Currency.GOLD, -price, reason, refId)),
                            extra)
                    .thenApply(r -> r.map(List::getFirst));
        }

        @Override
        public long repairQuote(UUID player, long price, boolean broken) {
            double stat =
                    kit.api()
                            .modifiers()
                            .apply(
                                    player,
                                    wiki.creeper.harbor.core.api.StatKey.of("repair_cost"),
                                    1.0);
            return wiki.creeper.harbor.world.ship.ShipMath.repairCost(
                    price, kit.balance().repairRate(broken), stat);
        }

        @Override
        public CompletableFuture<Result<Long, LedgerError>> repair(
                Player player, long cost, String refId, @Nullable SqlConsumer extra) {
            return charge(player, cost, LedgerReason.REPAIR, refId, extra);
        }

        /** Price 0 (e.g. rowboat repair): only the extra SQL, no ledger entry. */
        private CompletableFuture<Result<Long, LedgerError>> free(
                UUID uuid, @Nullable SqlConsumer extra) {
            long balance =
                    kit.api().players().get(uuid).map(p -> p.balance(Currency.GOLD)).orElse(0L);
            return kit.api()
                    .database()
                    .async(
                            c -> {
                                if (extra != null) {
                                    extra.accept(c);
                                }
                                return balance;
                            })
                    .handleAsync(
                            (value, error) -> {
                                if (error == null) {
                                    return Result.<Long, LedgerError>ok(value);
                                }
                                Throwable t = error;
                                while (t != null) {
                                    if (t instanceof LedgerException le) {
                                        return Result.<Long, LedgerError>err(le.error());
                                    }
                                    t = t.getCause();
                                }
                                kit.log().warn("결제 SQL 실패: {}", String.valueOf(error));
                                return Result.<Long, LedgerError>err(LedgerError.DATABASE);
                            },
                            kit.tasks().main());
        }
    }
}
