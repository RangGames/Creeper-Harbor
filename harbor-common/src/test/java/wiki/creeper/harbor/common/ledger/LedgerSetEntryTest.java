package wiki.creeper.harbor.common.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.model.Currency;

/** The diff entry an admin balance set posts (computed against the locked balance). */
class LedgerSetEntryTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static LedgerEntry entry(long current, long target) {
        return LedgerWriter.setEntry(
                PLAYER,
                Currency.GOLD,
                current,
                target,
                LedgerReason.ADMIN_GRANT,
                LedgerReason.ADMIN_TAKE,
                "set-1");
    }

    @Test
    void raisesWithGrant() {
        LedgerEntry e = entry(40, 100);
        assertNotNull(e);
        assertEquals(60, e.delta());
        assertEquals(LedgerReason.ADMIN_GRANT, e.reason());
        assertEquals("set-1", e.refId());
    }

    @Test
    void lowersWithTake() {
        LedgerEntry e = entry(100, 0);
        assertNotNull(e);
        assertEquals(-100, e.delta());
        assertEquals(LedgerReason.ADMIN_TAKE, e.reason());
    }

    @Test
    void equalBalanceIsNoOp() {
        assertNull(entry(55, 55));
    }

    @Test
    void overflowIsRejected() {
        assertThrows(ArithmeticException.class, () -> entry(Long.MIN_VALUE, 1));
    }
}
