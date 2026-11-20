package wiki.creeper.harbor.skript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.HarborId;

class IdsTest {

    @Test
    void acceptsFullShortAndNamespacedIds() {
        HarborId want = HarborId.parse("quest/main/ch1_first_cast");
        assertEquals(want, Ids.parse("quest/main/ch1_first_cast", "quest/").orElseThrow());
        assertEquals(want, Ids.parse("harbor:quest/main/ch1_first_cast", "quest/").orElseThrow());
        assertEquals(want, Ids.parse("main/ch1_first_cast", "quest/").orElseThrow());
        assertEquals(want, Ids.parse("  Quest/Main/CH1_First_Cast ", "quest/").orElseThrow());
    }

    @Test
    void rejectsEmptyAndMalformed() {
        assertTrue(Ids.parse("", "fish/").isEmpty());
        assertTrue(Ids.parse("fish/bad id!", "fish/").isEmpty());
    }

    @Test
    void flagNames() {
        assertTrue(Ids.flag("story/ch1_done"));
        assertTrue(Ids.flag("met_bawi"));
        assertFalse(Ids.flag("Story/Ch1"));
        assertFalse(Ids.flag("a//b"));
        assertFalse(Ids.flag(""));
    }
}
