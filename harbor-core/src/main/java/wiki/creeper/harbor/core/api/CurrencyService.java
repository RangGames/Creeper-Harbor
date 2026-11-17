package wiki.creeper.harbor.core.api;

import java.util.List;
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
 * 골드·물비늘 지급·차감. 원장(ledger)에 기록하는 즉시 트랜잭션으로 처리한다. 커밋된 뒤 메인 스레드에서 캐시 잔액을 바꾸고
 * CurrencyChangedEvent를 발생시키며, 돌려주는 future도 메인 스레드에서 완료된다.
 *
 * <p>상점·경매·시세 같은 규칙은 Economy 모듈에 둔다.
 */
public interface CurrencyService {

    /** @return 처리 후 잔액 */
    CompletableFuture<Result<Long, LedgerError>> give(
            UUID player, Currency currency, long amount, LedgerReason reason, @Nullable String refId);

    CompletableFuture<Result<Long, LedgerError>> take(
            UUID player, Currency currency, long amount, LedgerReason reason, @Nullable String refId);

    /**
     * 여러 플레이어·여러 재화의 변경과 추가 SQL을 한 트랜잭션으로 처리한다 (경매 낙찰: 구매자 차감 + 판매자 지급 + 수수료 + 경매 행 상태
     * 변경). 하나라도 실패하면 모두 취소된다. 진주(PEARL)는 다루지 않는다.
     *
     * @param extra 같은 트랜잭션에서 실행할 추가 SQL (null이면 없음)
     * @return 입력 순서대로 처리 후 잔액
     */
    CompletableFuture<Result<List<Long>, LedgerError>> atomic(List<LedgerEntry> entries, @Nullable SqlConsumer extra);

    /**
     * 한 트랜잭션으로 옮긴다 (경매 낙찰 등). 하나라도 실패하면 모두 취소된다.
     *
     * @return [보낸 사람 잔액, 받는 사람 잔액]
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
