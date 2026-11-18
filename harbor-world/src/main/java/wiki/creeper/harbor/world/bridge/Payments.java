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
 * 골드 결제 (선박·업그레이드·장식·수리). Economy가 있으면 ShopService.charge·RepairService(처리 중 거래 표시로 서버 이동을 미룸)를 쓰고,
 * 없으면 Core CurrencyService.atomic으로 원장과 World 테이블 갱신을 한 트랜잭션으로 처리한다 (docs 20 8.1 '골드 차감 규칙은
 * Economy'). 메인 스레드에서 부르고 future는 메인 스레드에서 완료된다.
 */
public interface Payments {

    /**
     * @param extra 같은 트랜잭션에서 실행할 SQL. 업무 규칙 위반이면 {@link LedgerException}(REJECTED)을 던진다
     */
    CompletableFuture<Result<Long, LedgerError>> charge(
            Player player,
            long price,
            LedgerReason reason,
            String refId,
            @Nullable SqlConsumer extra);

    /** 수리비 견적 (Economy RepairService: 가격 × 비율 × repair_cost 보정). */
    long repairQuote(UUID player, long price, boolean broken);

    /** 수리비 결제 (사유 REPAIR). */
    CompletableFuture<Result<Long, LedgerError>> repair(
            Player player, long cost, String refId, @Nullable SqlConsumer extra);

    /** 결제 실패 메시지 키. */
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

    /** Core만 쓰는 결제. */
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

        /** 가격 0 (나룻배 수리 등): 원장 없이 추가 SQL만. */
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
