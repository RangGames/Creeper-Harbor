package wiki.creeper.harbor.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.service.FraudMath;

class FraudMathTest {

    private static final EconomyBalance.FraudRules RULES =
            new EconomyBalance(Balance.of(Map.of())).fraud();

    @Test
    void ordinaryTradeIsClean() {
        FraudMath.Verdict v =
                FraudMath.evaluate(new FraudMath.Signals(false, 0, 1.2, false), RULES);
        assertEquals(0, v.score());
        assertFalse(v.flag());
        assertFalse(v.hold());
        assertEquals("none", v.kind());
    }

    @Test
    void linkedAccountsAreFlaggedButNotHeldAlone() {
        FraudMath.Verdict v = FraudMath.evaluate(new FraudMath.Signals(true, 0, 1.0, false), RULES);
        assertEquals(List.of("linked"), v.reasons());
        assertTrue(v.flag());
        assertFalse(v.hold());
    }

    @Test
    void repeatedPairCountsThisTrade() {
        // 7일에 3회 이상: 앞선 2회 + 이번 거래
        assertFalse(
                FraudMath.evaluate(new FraudMath.Signals(false, 1, 1, false), RULES)
                        .reasons()
                        .contains("pair_repeat"));
        assertTrue(
                FraudMath.evaluate(new FraudMath.Signals(false, 2, 1, false), RULES)
                        .reasons()
                        .contains("pair_repeat"));
    }

    @Test
    void washTradeBetweenAltsIsHeld() {
        // 같은 IP 계정끼리 기준가 0.5배 반복 거래 → 60 + 40 + 20 = 120 ≥ 70
        FraudMath.Verdict v = FraudMath.evaluate(new FraudMath.Signals(true, 2, 0.5, false), RULES);
        assertEquals(List.of("linked", "pair_repeat", "wash"), v.reasons());
        assertTrue(v.hold());
    }

    @Test
    void priceOutlierAndTrapAlt() {
        FraudMath.Verdict outlier =
                FraudMath.evaluate(new FraudMath.Signals(false, 0, 5.0, false), RULES);
        assertEquals("price_outlier", outlier.kind());
        assertTrue(outlier.flag());
        assertFalse(outlier.hold());
        FraudMath.Verdict trap = FraudMath.evaluate(new FraudMath.Signals(true, 0, 1, true), RULES);
        assertTrue(trap.reasons().contains("trap_alt"));
        assertTrue(trap.hold()); // 60 + 30
        // 통발 어종이어도 연결 계정이 아니면 표시하지 않음
        assertFalse(FraudMath.evaluate(new FraudMath.Signals(false, 0, 1, true), RULES).flag());
    }

    @Test
    void indexIsMedianOfRatios() {
        assertTrue(Double.isNaN(FraudMath.index(List.of())));
        assertEquals(1.5, FraudMath.index(List.of(3.0, 1.0, 1.5)), 1e-9);
        assertEquals(1.25, FraudMath.index(List.of(1.0, 1.5, 0.5, 2.0)), 1e-9);
    }
}
