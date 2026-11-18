package wiki.creeper.harbor.shop.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;

/**
 * 진주(유료 재화) 지갑 (docs 20 12.2). Core CurrencyService와 분리되어 있고, 골드·물비늘·어종으로 바꾸는 함수는 일부러 두지 않는다 (경제
 * 분리, '6. BM 기획' 1장). 모든 변경은 pearl_ledger 원장에 참조 ID 유니크로 기록되어 같은 참조 ID는 한 번만 처리된다.
 *
 * <p>future는 커밋 뒤 메인 스레드에서 완료되고, 그때 잔액 캐시와 {@code PearlBalanceChangedEvent}가 갱신된다. 결제로 들어오는 유료 진주는
 * Tebex 웹훅만 만들 수 있다 (이 API의 {@link #grant}는 무료 묶음).
 */
public interface PearlService {

    /**
     * 잔액.
     *
     * @param paid 유료 묶음 잔액 (청약철회 판단)
     * @param debt 환불·지불 거절 뒤 부족분 (잔액은 음수가 되지 않는다)
     */
    record Balance(long total, long paid, long debt) {

        public static final Balance EMPTY = new Balance(0, 0, 0);

        public long free() {
            return total - paid;
        }
    }

    /** 접속 중인 플레이어의 잔액 캐시 (메인 스레드). 접속 중이 아니면 {@link Balance#EMPTY}. */
    Balance balance(UUID player);

    /** DB에서 읽는다 (접속 여부와 무관). */
    CompletableFuture<Balance> balanceAsync(UUID player);

    /**
     * 진주를 쓴다 (무료 묶음 먼저, 그다음 오래된 유료 묶음). 상점 이용 정지(shop_flags) 중이면 {@link LedgerError#REJECTED}.
     *
     * @param refId 참조 ID (주문 ID 등, 1~64자)
     */
    CompletableFuture<Result<Long, LedgerError>> spend(
            UUID player, long amount, LedgerReason reason, String refId);

    /** 무료 묶음으로 준다 (패스 보상·이벤트·관리자). */
    CompletableFuture<Result<Long, LedgerError>> grant(
            UUID player, long amount, LedgerReason reason, String refId);

    /** 상점 이용 정지 중인지 (접속 중 캐시, 메인 스레드). */
    boolean locked(UUID player);
}
