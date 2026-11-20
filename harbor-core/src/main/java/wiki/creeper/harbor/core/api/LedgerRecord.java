package wiki.creeper.harbor.core.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;

/**
 * 원장 한 줄 (읽기 전용, 추가 제안). {@link CurrencyService#history}가 돌려준다.
 *
 * @param delta 바뀐 양 (지급은 양수, 차감은 음수)
 * @param balanceAfter 처리 후 잔액
 * @param reason 사유 이름 (LedgerReason 이름. 예전 사유가 enum에서 빠졌을 수 있어 글로 둔다)
 */
public record LedgerRecord(
        long id,
        Currency currency,
        long delta,
        long balanceAfter,
        String reason,
        @Nullable String refId,
        Instant createdAt) {

    /** 사유 enum (모르는 이름이면 비어 있음). */
    public Optional<LedgerReason> reasonType() {
        try {
            return Optional.of(LedgerReason.valueOf(reason));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
