package wiki.creeper.harbor.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.service.MarketMath;

class MarketMathTest {

    private static final EconomyBalance BALANCE = new EconomyBalance(Balance.of(Map.of()));

    @Test
    void coefficientFollowsBalanceFormula() {
        // '7. 밸런스 기획' 6.2: M = clamp(1 − 0.35 × (S/S₀ − 1), 0.5, 1.2)
        assertEquals(1.2, MarketMath.coefficient(0, 100, BALANCE), 1e-9);
        assertEquals(1.0, MarketMath.coefficient(100, 100, BALANCE), 1e-9);
        assertEquals(0.65, MarketMath.coefficient(200, 100, BALANCE), 1e-9);
        assertEquals(0.5, MarketMath.coefficient(1000, 100, BALANCE), 1e-9);
        assertEquals(1.175, MarketMath.coefficient(50, 100, BALANCE), 1e-9);
    }

    private static FishDef fish(String id, Grade grade, int weight) {
        return new FishDef(
                FishId.parse("fish/" + id),
                1,
                id,
                List.of(),
                grade,
                RegionId.parse("region/harbor"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                null,
                10,
                20,
                5,
                weight,
                false,
                "harbor:fish/x",
                1);
    }

    @Test
    void expectedShareUsesGradeAndSpeciesWeights() {
        FishDef a = fish("a", Grade.COMMON, 100);
        FishDef b = fish("b", Grade.COMMON, 300);
        FishDef c = fish("c", Grade.UNCOMMON, 100);
        Map<Grade, Double> weights =
                Map.of(Grade.COMMON, 60.0, Grade.UNCOMMON, 25.0, Grade.RARE, 10.0, Grade.EPIC, 4.0);
        // 지역에 일반·고급만 있으므로 일반 60/85, 그 안에서 a는 100/400
        assertEquals(
                60.0 / 85 * 0.25, MarketMath.expectedShare(a, List.of(a, b, c), weights), 1e-9);
        assertEquals(25.0 / 85, MarketMath.expectedShare(c, List.of(a, b, c), weights), 1e-9);
    }
}
