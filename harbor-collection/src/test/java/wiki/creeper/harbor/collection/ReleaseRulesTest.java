package wiki.creeper.harbor.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.content.ReleaseRules;
import wiki.creeper.harbor.collection.content.ReleaseRules.Verdict;
import wiki.creeper.harbor.common.model.Grade;

/** 방류 규칙 (docs 20 4.4): climax_done 전에는 잠김, 어종 아이템만, release_only 제외, 희귀 이상만 보상. */
class ReleaseRulesTest {

    @Test
    void lockedBeforeClimax() {
        assertEquals(Verdict.LOCKED, ReleaseRules.check(false, true, false));
        // 잠김이 먼저 (아이템이 무엇이든)
        assertEquals(Verdict.LOCKED, ReleaseRules.check(false, false, false));
    }

    @Test
    void onlyFishItems() {
        assertEquals(Verdict.NOT_FISH, ReleaseRules.check(true, false, false));
        assertEquals(Verdict.RELEASE_ONLY, ReleaseRules.check(true, true, true));
        assertEquals(Verdict.ALLOWED, ReleaseRules.check(true, true, false));
    }

    @Test
    void rewardsFromRareUp() {
        ReleaseRules rules = ReleaseRules.DEFAULT;
        assertFalse(rules.rewards(Grade.COMMON));
        assertFalse(rules.rewards(Grade.UNCOMMON));
        assertTrue(rules.rewards(Grade.RARE));
        assertTrue(rules.rewards(Grade.LEGENDARY));
        assertEquals("climax_done", rules.flag());
        assertEquals("size_luck", rules.buffStat().toString());
        assertEquals(30, rules.buffMinutes());
    }
}
