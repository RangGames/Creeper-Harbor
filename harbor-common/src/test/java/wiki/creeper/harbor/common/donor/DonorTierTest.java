package wiki.creeper.harbor.common.donor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DonorTierTest {

    private static final Instant NOW = Instant.parse("2027-10-01T00:00:00Z");

    @Test
    void activeUntilPeriodEndPlusGrace() {
        Instant end = NOW.minus(Duration.ofDays(2));
        assertEquals(DonorTier.FLEET, DonorTier.effective(DonorTier.FLEET, "ACTIVE", end, NOW));
        assertEquals(
                DonorTier.HARBOR,
                DonorTier.effective(DonorTier.HARBOR, "CANCEL_PENDING", end, NOW));
        Instant expired = NOW.minus(DonorTier.GRACE);
        assertEquals(DonorTier.NONE, DonorTier.effective(DonorTier.FLEET, "ACTIVE", expired, NOW));
    }

    @Test
    void endedOrMissingIsNone() {
        Instant end = NOW.plus(Duration.ofDays(10));
        assertEquals(DonorTier.NONE, DonorTier.effective(DonorTier.FLEET, "ENDED", end, NOW));
        assertEquals(DonorTier.NONE, DonorTier.effective(DonorTier.FLEET, null, end, NOW));
        assertEquals(DonorTier.NONE, DonorTier.effective(DonorTier.FLEET, "ACTIVE", null, NOW));
        assertEquals(DonorTier.NONE, DonorTier.effective(DonorTier.NONE, "ACTIVE", end, NOW));
    }
}
