package wiki.creeper.harbor.common.ledger;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Currency;

/**
 * One currency change to record in the ledger.
 *
 * @param delta change amount (+ grant, - deduct). 0 is not allowed.
 * @param refId reference ID (quest completion ID, auction win ID, payment transaction ID). The same
 *     (player, currency, reason, reference ID) is recorded only once.
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
