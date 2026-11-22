package wiki.creeper.harbor.common.ledger;

/** Reason a ledger write failed. */
public enum LedgerError {
    /** Insufficient balance (new balance would be negative). */
    INSUFFICIENT_FUNDS,
    /** Already processed with the same reference ID (prevents double grants). */
    DUPLICATE,
    /** No balance store handles this currency. */
    UNSUPPORTED_CURRENCY,
    /**
     * Extra SQL run alongside rejected it for a business rule violation (daily limit exceeded,
     * etc.).
     */
    REJECTED,
    /** DB error. */
    DATABASE,
}
