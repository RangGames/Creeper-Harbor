package wiki.creeper.harbor.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.season.api.LimitResult;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

class LimitAndTitleIdTest {

    @Test
    void limitResultMath() {
        LimitResult r = new LimitResult(true, 2, 3, 4);
        assertEquals(1, r.remaining());
        assertFalse(r.exhausted());
        LimitResult full = new LimitResult(false, 3, 3, 4);
        assertEquals(0, full.remaining());
        assertTrue(full.exhausted());
        // When the limit shrinks below the used count, remaining does not go negative
        assertEquals(0, new LimitResult(false, 5, 3, 4).remaining());
    }

    @Test
    void limitKeys() {
        assertEquals("boss:swamp_catfish", WeeklyLimitService.bossKey("swamp_catfish"));
        assertEquals("craft:legend_bait", WeeklyLimitService.craftKey("legend_bait"));
        assertThrows(IllegalArgumentException.class, () -> WeeklyLimitService.requireKey("Boss:X"));
        assertThrows(IllegalArgumentException.class, () -> WeeklyLimitService.requireKey(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> WeeklyLimitService.requireKey("a".repeat(97)));
    }

    @Test
    void titleIdAcceptsShortForms() {
        TitleId full = TitleId.of("harbor:title/codex_25");
        assertEquals(full, TitleId.of("title/codex_25"));
        assertEquals(full, TitleId.of("codex_25"));
        assertEquals("codex_region/harbor", TitleId.of("codex_region/harbor").shortName());
        assertEquals(
                "harbor:title/codex_region/harbor", TitleId.of("codex_region/harbor").toString());
        assertTrue(TitleId.tryOf("Bad Title").isEmpty());
    }
}
