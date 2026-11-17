package wiki.creeper.harbor.common.ledger;

/** 트랜잭션 안에서 원장 쓰기를 중단할 때 던진다. 트랜잭션은 롤백된다. */
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
