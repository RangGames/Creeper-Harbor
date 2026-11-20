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
 * 골드·물비늘 지급·차감. 원장(ledger)에 기록하는 즉시 트랜잭션으로 처리한다. 커밋된 뒤 메인 스레드에서 캐시 잔액을 바꾸고 CurrencyChangedEvent를
 * 발생시키며, 돌려주는 future도 메인 스레드에서 완료된다.
 *
 * <p>상점·경매·시세 같은 규칙은 Economy 모듈에 둔다.
 */
public interface CurrencyService {

    /**
     * @return 처리 후 잔액
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
     * 여러 플레이어·여러 재화의 변경과 추가 SQL을 한 트랜잭션으로 처리한다 (경매 낙찰: 구매자 차감 + 판매자 지급 + 수수료 + 경매 행 상태 변경). 하나라도
     * 실패하면 모두 취소된다. 진주(PEARL)는 다루지 않는다.
     *
     * @param extra 같은 트랜잭션에서 실행할 추가 SQL (null이면 없음)
     * @return 입력 순서대로 처리 후 잔액
     */
    CompletableFuture<Result<List<Long>, LedgerError>> atomic(
            List<LedgerEntry> entries, @Nullable SqlConsumer extra);

    /** 이번 시즌 잔액. 이 서버에 접속 중이면 캐시 값, 아니면 DB에서 읽는다 (다른 서버 접속자·오프라인 포함, 행이 없으면 0). 메인 스레드에서 완료된다. */
    CompletableFuture<Long> balance(UUID player, Currency currency);

    /**
     * 이번 시즌 잔액 순위 (잔액이 0보다 큰 플레이어만, 같으면 이름순). 상위 {@value #TOP_CACHED}위까지는 30초 캐시라 조금 늦을 수 있다. 메인
     * 스레드에서 완료된다.
     *
     * @param offset 건너뛸 수 (0 = 1위부터)
     * @param limit 최대 줄 수 (1~100)
     */
    CompletableFuture<List<BalanceRank>> top(Currency currency, int offset, int limit);

    /**
     * 최근 원장 기록 (새 것부터, 추가 제안: 플레이어 거래 내역 화면). 시즌을 가리지 않는다. 메인 스레드에서 완료된다.
     *
     * @param limit 최대 줄 수 (1~200)
     */
    CompletableFuture<List<LedgerRecord>> history(UUID player, Currency currency, int limit);

    /** 상위 캐시 크기. */
    int TOP_CACHED = 100;

    /** 이번 시즌 순위 (1부터). 잔액이 0이거나 행이 없으면 빈 값. 메인 스레드에서 완료된다. */
    CompletableFuture<Optional<Integer>> rank(UUID player, Currency currency);

    /**
     * 잔액을 amount로 맞춘다 (관리 명령). 지금 잔액과의 차이를 ADMIN_GRANT·ADMIN_TAKE로 원장에 기록한다. 잔액을 읽고 쓰는 사이에 다른 변경이
     * 끼어들면 그만큼 어긋날 수 있다 (운영자 전용, 처리 후 잔액을 돌려준다).
     *
     * @return 처리 후 잔액. 차이가 없으면 지금 잔액
     */
    CompletableFuture<Result<Long, LedgerError>> set(
            UUID player, Currency currency, long amount, @Nullable String refId);

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
