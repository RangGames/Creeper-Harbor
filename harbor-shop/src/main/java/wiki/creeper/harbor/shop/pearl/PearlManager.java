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
 * 진주 지갑 (PearlService 구현, docs 20 12.4 '진주 원장'). 모든 진주 사용처(상점·패스·보물함·선물·닉네임)는 {@link #transact}로 주문
 * 기록·진주 차감·소유 기록을 한 트랜잭션에 넣는다. 커밋 뒤에만 캐시와 PearlBalanceChangedEvent를 갱신한다.
 */
public final class PearlManager implements PearlService {

    /** 실패 사유와 보여 줄 문구 키. */
    public record Failure(LedgerError error, String messageKey) {}

    /** 트랜잭션 작업. */
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

    /** 관리자 회수 (ADMIN_TAKE). 상점 이용 정지 중이어도 처리한다. */
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

    /** 결과 래퍼 (Failure → LedgerError 변환 도우미). */
    public record Outcome<T>(Result<T, Failure> result) {

        public Result<T, LedgerError> mapError() {
            return switch (result) {
                case Result.Ok<T, Failure>(T value) -> Result.ok(value);
                case Result.Err<T, Failure>(Failure f) -> Result.err(f.error());
            };
        }
    }

    /**
     * 가상 스레드에서 한 트랜잭션으로 실행하고, 커밋 뒤 메인 스레드에서 바뀐 지갑 캐시·이벤트를 갱신한 다음 결과를 돌려준다. {@link
     * ShopReject}·{@link LedgerException}은 롤백 후 실패로 돌려준다.
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

    /** 커밋된 트랜잭션 결과. 커밋 뒤 {@link #afterCommit(Committed)}로 캐시를 맞춘다. */
    public record Committed<T>(
            T value, List<PearlLedger.Applied> applied, List<PearlLedger.Change> changes) {}

    /**
     * 지금 스레드에서 한 트랜잭션으로 실행한다 (가상 스레드 전용: 웹훅 처리기, 시즌 전환 훅). 캐시 갱신은 호출한 쪽이 메인 스레드에서 {@link
     * #afterCommit(Committed)}로 한다.
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

    /** 커밋된 진주 변경을 캐시에 반영한다 (메인 스레드). 웹훅 처리처럼 직접 트랜잭션을 돌린 쪽도 부른다. */
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

    /** DB에서 지갑·정지 상태를 다시 읽어 캐시에 넣는다 (다른 서버에서 처리된 결제·환불). */
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
