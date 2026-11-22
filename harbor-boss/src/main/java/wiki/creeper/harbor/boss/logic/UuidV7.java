package wiki.creeper.harbor.boss.logic;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID v7 (time-ordered UUID, RFC 9562). Used for boss_encounter.encounter_id and
 * boss_reward_pending.id.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID next() {
        return of(System.currentTimeMillis(), RANDOM.nextLong(), RANDOM.nextLong());
    }

    /** For tests: builds one from a given time and random value. */
    public static UUID of(long epochMillis, long randA, long randB) {
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= randA & 0x0FFFL;
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10
        return new UUID(msb, lsb);
    }

    /** Timestamp of a v7 UUID (milliseconds). */
    public static long millis(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}
