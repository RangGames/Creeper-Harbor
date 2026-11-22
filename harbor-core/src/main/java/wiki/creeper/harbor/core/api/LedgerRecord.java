package wiki.creeper.harbor.core.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;

/**
 * One ledger row (read-only, proposed). Returned by {@link CurrencyService#history}.
 *
 * @param delta amount changed (positive for grants, negative for deductions)
 * @param balanceAfter balance after the operation
 * @param reason reason name (a LedgerReason name; kept as text because old reasons may have been
 *     removed from the enum)
 */
public record LedgerRecord(
        long id,
        Currency currency,
        long delta,
        long balanceAfter,
        String reason,
        @Nullable String refId,
        Instant createdAt) {

    /** Reason enum (empty if the name is unknown). */
    public Optional<LedgerReason> reasonType() {
        try {
            return Optional.of(LedgerReason.valueOf(reason));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
