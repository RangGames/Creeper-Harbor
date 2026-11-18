package wiki.creeper.harbor.shop.donor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.donor.DonorTier;

class DonorGraceTest {

    private static final Duration GRACE = Duration.ofDays(3);
    private static final Instant END = Instant.parse("2027-07-01T00:00:00Z");

    @Test
    void benefitsLastUntilPeriodEndPlusThreeDays() {
        assertEquals(
                DonorTier.FLEET,
                DonorGrace.effective(
                        DonorGrace.State.ACTIVE, DonorTier.FLEET, END, END.minusSeconds(1), GRACE));
        assertEquals(
                DonorTier.FLEET,
                DonorGrace.effective(
                        DonorGrace.State.ACTIVE,
                        DonorTier.FLEET,
                        END,
                        END.plus(Duration.ofDays(2)),
                        GRACE));
        assertEquals(
                DonorTier.NONE,
                DonorGrace.effective(
                        DonorGrace.State.ACTIVE, DonorTier.FLEET, END, END.plus(GRACE), GRACE));
        // 해지 요청도 기간 끝까지 (+유예) 유지
        assertEquals(
                DonorTier.HARBOR,
                DonorGrace.effective(
                        DonorGrace.State.CANCEL_PENDING,
                        DonorTier.HARBOR,
                        END,
                        END.minusSeconds(60),
                        GRACE));
        assertEquals(
                DonorTier.NONE,
                DonorGrace.effective(
                        DonorGrace.State.ENDED,
                        DonorTier.HARBOR,
                        END,
                        END.minusSeconds(60),
                        GRACE));
        assertEquals(
                DonorTier.NONE,
                DonorGrace.effective(DonorGrace.State.ACTIVE, DonorTier.HARBOR, null, END, GRACE));
    }

    @Test
    void sweepEndsOnlyAfterGrace() {
        assertFalse(
                DonorGrace.shouldEnd(
                        DonorGrace.State.CANCEL_PENDING, END, END.plus(Duration.ofDays(2)), GRACE));
        assertTrue(
                DonorGrace.shouldEnd(DonorGrace.State.CANCEL_PENDING, END, END.plus(GRACE), GRACE));
        assertFalse(DonorGrace.shouldEnd(DonorGrace.State.ENDED, END, END.plus(GRACE), GRACE));
    }

    @Test
    void earlyRenewalExtendsFromCurrentEnd() {
        Duration month = Duration.ofDays(30);
        assertEquals(
                END.plus(month), DonorGrace.renewedEnd(END, END.minus(Duration.ofDays(5)), month));
        Instant late = END.plus(Duration.ofDays(1));
        assertEquals(late.plus(month), DonorGrace.renewedEnd(END, late, month));
        assertEquals(late.plus(month), DonorGrace.renewedEnd(null, late, month));
    }
}
