package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HP formula from '7. Balance design' chapter 9. */
class HpScalingTest {

    @Test
    void partyFormula() {
        // "That One" (그놈) 2,000 · intro +60%
        assertEquals(2000, HpScaling.partyMaxHp(2000, 0.6, 1), 1e-9);
        assertEquals(3200, HpScaling.partyMaxHp(2000, 0.6, 2), 1e-9);
        // Moray eel 3,000 · sea +50% · 4 players
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
