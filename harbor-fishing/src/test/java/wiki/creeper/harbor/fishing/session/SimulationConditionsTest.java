package wiki.creeper.harbor.fishing.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.ItemId;

class SimulationConditionsTest {

    @Test
    void emptyTextIsTheDefault() {
        assertEquals(Simulation.Conditions.DEFAULT, Simulation.Conditions.parse(""));
        assertEquals(Simulation.Conditions.DEFAULT, Simulation.Conditions.parse("   "));
    }

    @Test
    void parsesEveryKey() {
        Simulation.Conditions c =
                Simulation.Conditions.parse(
                        "time=night weather=RAIN moon=full bait=harbor:item/bait/earthworm tier=2");
        assertEquals("night", c.time());
        assertEquals("rain", c.weather());
        assertEquals("full", c.moon());
        assertEquals(ItemId.parse("harbor:item/bait/earthworm"), c.bait());
        assertEquals(2, c.tier());
        assertEquals("", Simulation.Conditions.parse("moon=none").moon());
    }

    @Test
    void rejectsUnknownTokens() {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Simulation.Conditions.parse("time=noon"));
        assertEquals("time=noon", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Simulation.Conditions.parse("tier=4"));
        assertThrows(IllegalArgumentException.class, () -> Simulation.Conditions.parse("foo"));
    }
}
