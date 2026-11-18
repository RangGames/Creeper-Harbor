package wiki.creeper.harbor.shop.pearl;

/** 업무 규칙 위반으로 트랜잭션을 멈출 때 던진다 (이미 가짐, 한도 초과, 조건 미달 …). 트랜잭션은 롤백되고, 플레이어에게는 messageKey 문구를 보여 준다. */
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
