package wiki.creeper.harbor.shop.util;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID v7 (시간순 정렬되는 UUID, RFC 9562). 주문 ID(shop_order.order_id)에 쓴다. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID next() {
        return of(System.currentTimeMillis(), RANDOM.nextLong(), RANDOM.nextLong());
    }

    static UUID of(long millis, long randA, long randB) {
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L | (randA & 0x0FFFL);
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
