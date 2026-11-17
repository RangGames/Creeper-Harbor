package wiki.creeper.harbor.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;

class HarborIdTest {

    @Test
    void parsesWithAndWithoutNamespace() {
        assertEquals(new HarborId("harbor", "fish/harbor_mackerel"), HarborId.parse("fish/harbor_mackerel"));
        assertEquals(new HarborId("harbor", "fish/harbor_mackerel"), HarborId.parse("harbor:fish/harbor_mackerel"));
        assertEquals("harbor:item/rod/coral_carbon", HarborId.parse("item/rod/coral_carbon").toString());
    }

    @Test
    void rejectsBadFormat() {
        assertThrows(IllegalArgumentException.class, () -> HarborId.parse("Fish/Mackerel"));
        assertThrows(IllegalArgumentException.class, () -> HarborId.parse("fish//x"));
        assertThrows(IllegalArgumentException.class, () -> HarborId.parse("fish/x-y"));
        assertTrue(HarborId.tryParse("a b").isEmpty());
    }

    @Test
    void typedIdsCheckPrefix() {
        assertEquals("harbor_mackerel", FishId.parse("fish/harbor_mackerel").id().name());
        assertThrows(IllegalArgumentException.class, () -> FishId.parse("item/bait/earthworm"));
        assertThrows(IllegalArgumentException.class, () -> ItemId.parse("item/"));
    }
}
