package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** '7. 밸런스 기획' 9장 체력 공식. */
class HpScalingTest {

    @Test
    void partyFormula() {
        // 그놈 2,000 · 입문 +60%
        assertEquals(2000, HpScaling.partyMaxHp(2000, 0.6, 1), 1e-9);
        assertEquals(3200, HpScaling.partyMaxHp(2000, 0.6, 2), 1e-9);
        // 곰치 3,000 · 해역 +50% · 4인
        assertEquals(7500, HpScaling.partyMaxHp(3000, 0.5, 4), 1e-9);
        assertEquals(2000, HpScaling.partyMaxHp(2000, 0.6, 0), 1e-9);
    }

    @Test
    void worldFormulaHasMinimumTen() {
        assertEquals(15000, HpScaling.worldMaxHp(1500, 3, 10), 1e-9);
        assertEquals(15000, HpScaling.worldMaxHp(1500, 10, 10), 1e-9);
        assertEquals(225000, HpScaling.worldMaxHp(1500, 150, 10), 1e-9);
    }

    @Test
    void effectiveParticipantsCountsRecentOnly() {
        Map<UUID, Long> last =
                Map.of(new UUID(0, 1), 900L, new UUID(0, 2), 2150L, new UUID(0, 3), 2200L);
        assertEquals(2, HpScaling.effectiveParticipants(last, 2200, 1200));
        assertEquals(3, HpScaling.effectiveParticipants(last, 2200, 1200 + 200));
    }
}
