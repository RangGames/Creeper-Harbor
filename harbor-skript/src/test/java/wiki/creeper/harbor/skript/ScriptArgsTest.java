package wiki.creeper.harbor.skript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptArgsTest {

    @Test
    void pairsKeepOrderAndSkipBadEntries() {
        Map<String, String> m =
                ScriptArgs.pairs(List.of("name=Steve", "bad", "=x", "Amount= 3", "a b=1", "u=x=y"));
        assertEquals(List.of("name", "amount", "u"), List.copyOf(m.keySet()));
        assertEquals(" 3", m.get("amount"));
        assertEquals("x=y", m.get("u"));
    }

    @Test
    void typedValues() {
        assertEquals(1000L, ScriptArgs.typed("1000"));
        assertEquals(1.5, ScriptArgs.typed("1.5"));
        assertEquals(true, ScriptArgs.typed("true"));
        assertEquals("NaN", ScriptArgs.typed("NaN"));
        assertEquals("abc", ScriptArgs.typed("abc"));
    }

    @Test
    void sourceIsAlwaysScriptScoped() {
        assertEquals("skript:buff", ScriptArgs.source(" buff ").orElseThrow());
        assertEquals("skript:buff", ScriptArgs.source("skript:buff").orElseThrow());
        assertEquals("skript:skill:x", ScriptArgs.source("skill:x").orElseThrow());
        assertTrue(ScriptArgs.source("  ").isEmpty());
    }

    @Test
    void soundKeyDefaultsToHarborNamespace() {
        assertEquals("harbor:fishing/bite", ScriptArgs.soundKey("fishing/bite"));
        assertEquals(
                "minecraft:entity.player.levelup",
                ScriptArgs.soundKey("minecraft:entity.player.levelup"));
    }
}
