package wiki.creeper.harbor.common.ledger;

/** Thrown to abort a ledger write inside a transaction. The transaction is rolled back. */
public final class LedgerException extends RuntimeException {

    private final LedgerError error;

    public LedgerException(LedgerError error, String message) {
        super(message);
        this.error = error;
    }

    public LedgerError error() {
        return error;
    }
}
