package wiki.creeper.harbor.shop.pearl;

/**
 * Thrown to stop a transaction on a business rule violation (already owned, limit exceeded,
 * condition unmet …). The transaction is rolled back and the player sees the messageKey text.
 */
public final class ShopReject extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String messageKey;

    public ShopReject(String messageKey) {
        super(messageKey, null, false, false);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
