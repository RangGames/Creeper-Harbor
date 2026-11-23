package wiki.creeper.harbor.skript.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdminKeysTest {

    @Test
    void adminPatternsAreDetected() {
        assertTrue(AdminKeys.isAdmin("harbor admin complete [the] harbor quest %string%"));
        assertTrue(AdminKeys.isAdmin("[the] x", "Harbor Admin set pity"));
        assertFalse(AdminKeys.isAdmin("harbor give %number% gold to %player%"));
        assertFalse(AdminKeys.isAdmin("harbor administer"));
    }

    @Test
    void actionKeysDropHarborAndAdmin() {
        assertEquals("skript.complete_quest", AdminKeys.action("harbor admin complete quest"));
        assertEquals("skript.force_start_boss", AdminKeys.action("admin force start harbor boss"));
        assertEquals(
                "skript.force_end_boss_victory",
                AdminKeys.action("admin force end harbor boss victory"));
        assertEquals("skript.region_travel", AdminKeys.action("admin harbor region travel"));
    }
}
