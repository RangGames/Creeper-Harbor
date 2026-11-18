package wiki.creeper.harbor.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.account.AccountLinkReader;

class AltHeuristicsTest {

    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void partnersExcludeSelfAndDuplicates() {
        assertEquals(
                List.of(A, B), List.copyOf(AltHeuristics.partners(SELF, List.of(SELF, A, A, B))));
        assertTrue(AltHeuristics.partners(SELF, List.of(SELF)).isEmpty());
        assertEquals(3, AltHeuristics.accounts(SELF, List.of(A, B, SELF)));
        assertEquals(1, AltHeuristics.accounts(SELF, List.of()));
    }

    @Test
    void alertsAtThresholdUnlessSharedIp() {
        assertFalse(AltHeuristics.shouldAlert(2, false, 0, 3));
        assertTrue(AltHeuristics.shouldAlert(3, false, 0, 3));
        assertFalse(AltHeuristics.shouldAlert(5, true, 0, 3));
    }

    @Test
    void alertsAgainOnlyWhenAccountCountGrows() {
        assertFalse(AltHeuristics.shouldAlert(3, false, 3, 3));
        assertTrue(AltHeuristics.shouldAlert(4, false, 3, 3));
    }

    @Test
    void pairOrderIsStableRegardlessOfArgumentOrder() {
        String[] ab = AccountLinkReader.orderedPair(A, SELF);
        String[] ba = AccountLinkReader.orderedPair(SELF, A);
        assertEquals(List.of(ab), List.of(ba));
        assertTrue(ab[0].compareTo(ab[1]) < 0);
    }
}
