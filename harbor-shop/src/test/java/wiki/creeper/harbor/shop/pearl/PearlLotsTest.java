package wiki.creeper.harbor.shop.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PearlLotsTest {

    @Test
    void freeLotsFirstThenOldestPaid() {
        List<PearlLots.Lot> lots =
                List.of(
                        new PearlLots.Lot(1, true, 500),
                        new PearlLots.Lot(2, false, 100),
                        new PearlLots.Lot(3, true, 990),
                        new PearlLots.Lot(4, false, 50));
        List<PearlLots.Take> takes = PearlLots.allocate(lots, 700);
        assertEquals(
                List.of(
                        new PearlLots.Take(2, false, 100),
                        new PearlLots.Take(4, false, 50),
                        new PearlLots.Take(1, true, 500),
                        new PearlLots.Take(3, true, 50)),
                takes);
        assertEquals(550, PearlLots.paidPart(takes));
    }

    @Test
    void insufficientLotsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PearlLots.allocate(List.of(new PearlLots.Lot(1, true, 10)), 11));
        assertEquals(List.of(), PearlLots.allocate(List.of(), 0));
    }
}
