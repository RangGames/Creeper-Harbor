package wiki.creeper.harbor.boss.logic;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID v7 (시간 순서 정렬되는 UUID, RFC 9562). boss_encounter.encounter_id, boss_reward_pending.id에 쓴다. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID next() {
        return of(System.currentTimeMillis(), RANDOM.nextLong(), RANDOM.nextLong());
    }

    /** 테스트용: 시각과 난수를 정해서 만든다. */
    public static UUID of(long epochMillis, long randA, long randB) {
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= randA & 0x0FFFL;
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10
        return new UUID(msb, lsb);
    }

    /** v7 UUID의 시각 (밀리초). */
    public static long millis(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}
