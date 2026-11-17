package wiki.creeper.harbor.common.ledger;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Currency;

/**
 * 원장에 남길 재화 변경 한 건.
 *
 * @param delta 변화량 (+ 지급, - 차감). 0은 허용하지 않는다.
 * @param refId 참조 ID (퀘스트 완료 ID, 경매 낙찰 ID, 결제 거래 ID). 같은 (플레이어, 재화, 사유, 참조 ID)는 한 번만 기록된다.
 */
public record LedgerEntry(
        UUID player, Currency currency, long delta, LedgerReason reason, @Nullable String refId) {

    public static final int MAX_REF_LENGTH = 64;

    public LedgerEntry {
        if (delta == 0) {
            throw new IllegalArgumentException("변화량이 0입니다");
        }
        if (refId != null && (refId.isEmpty() || refId.length() > MAX_REF_LENGTH)) {
            throw new IllegalArgumentException("참조 ID는 1~" + MAX_REF_LENGTH + "자여야 합니다");
        }
    }
}
