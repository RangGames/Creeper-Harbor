package wiki.creeper.harbor.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.economy.service.AuctionSort;
import wiki.creeper.harbor.economy.service.MarketTrend;
import wiki.creeper.harbor.economy.service.SellProtection;
import wiki.creeper.harbor.economy.service.ShopQuantity;

class EconomyUxLogicTest {

    @Test
    void protectionPriority() {
        assertEquals(SellProtection.Reason.LOCKED, SellProtection.of(5, 5, true, true));
        assertEquals(SellProtection.Reason.UNIQUE, SellProtection.of(3, 5, true, false));
        assertEquals(SellProtection.Reason.MAX_QUALITY, SellProtection.of(5, 5, false, false));
        assertEquals(SellProtection.Reason.NONE, SellProtection.of(4, 5, false, false));
        assertFalse(SellProtection.Reason.NONE.protects());
    }

    @Test
    void sortCyclesThroughAll() {
        AuctionSort s = AuctionSort.LATEST;
        for (int i = 0; i < AuctionSort.values().length; i++) {
            s = s.next();
        }
        assertEquals(AuctionSort.LATEST, s);
        assertEquals("price_asc", AuctionSort.PRICE_ASC.key());
        assertTrue(AuctionSort.GRADE.orderBy().startsWith("FIELD(grade"));
    }

    @Test
    void trend() {
        assertEquals(0.2, MarketTrend.change(1.1, 0.9), 1e-9);
        assertEquals(0, MarketTrend.change(1.1, Double.NaN));
        assertEquals(MarketTrend.Direction.UP, MarketTrend.direction(0.05, 0.01));
        assertEquals(MarketTrend.Direction.DOWN, MarketTrend.direction(-0.05, 0.01));
        assertEquals(MarketTrend.Direction.FLAT, MarketTrend.direction(0.005, 0.01));
        assertTrue(Double.isNaN(MarketTrend.oldest(List.of())));
        assertEquals(0.8, MarketTrend.oldest(List.of(0.8, 0.9)));
        Set<String> top =
                MarketTrend.topMovers(Map.of("a", 0.3, "b", -0.5, "c", 0.001, "d", 0.1), 2, 0.01);
        assertEquals(Set.of("a", "b"), top);
    }

    @Test
    void shopQuantity() {
        assertEquals(OptionalInt.of(1), ShopQuantity.amount(ShopQuantity.Click.LEFT, 64));
        assertEquals(OptionalInt.of(16), ShopQuantity.amount(ShopQuantity.Click.SHIFT_LEFT, 16));
        assertTrue(ShopQuantity.amount(ShopQuantity.Click.RIGHT, 64).isEmpty());
        assertEquals(OptionalInt.of(20), ShopQuantity.parse(" 20 ", 64));
        assertTrue(ShopQuantity.parse("0", 64).isEmpty());
        assertTrue(ShopQuantity.parse("65", 64).isEmpty());
        assertTrue(ShopQuantity.parse("abc", 64).isEmpty());
    }
}
