package wiki.creeper.harbor.shop.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.content.PassDef;

class PassMathTest {

    private static final PassDef PASS =
            new PassDef(
                    HarborId.parse("pass/s1"),
                    1,
                    "테스트",
                    50,
                    1000,
                    new PassDef.Prices(990, 1990, 1000, 100),
                    10,
                    Map.of("catch.rare", 15),
                    List.of(
                            new PassDef.Reward(1, PassDef.Track.FREE, 0, null, 30, null),
                            new PassDef.Reward(1, PassDef.Track.PREMIUM, 0, null, 100, null),
                            new PassDef.Reward(
                                    1,
                                    PassDef.Track.PREMIUM_PLUS,
                                    0,
                                    HarborId.parse("cosmetic/frame"),
                                    0,
                                    null),
                            new PassDef.Reward(5, PassDef.Track.FREE, 0, null, 30, null),
                            new PassDef.Reward(
                                    5,
                                    PassDef.Track.PREMIUM,
                                    0,
                                    HarborId.parse("cosmetic/emote"),
                                    0,
                                    null),
                            new PassDef.Reward(20, PassDef.Track.PREMIUM, 0, null, 150, null)));

    @Test
    void levelComesFromCumulativeExpPlusBoughtAndCapsAtMax() {
        assertEquals(0, PassMath.level(PASS, 999, 0));
        assertEquals(1, PassMath.level(PASS, 1000, 0));
        assertEquals(12, PassMath.level(PASS, 2500, 10));
        assertEquals(50, PassMath.level(PASS, 60_000, 0));
        assertEquals(50, PassMath.level(PASS, 45_000, 10));
        assertEquals(500, PassMath.progress(PASS, 2500, 0));
        assertEquals(1000, PassMath.progress(PASS, 50_000, 0));
    }

    @Test
    void levelPurchaseOnlyUpToMax() {
        assertEquals(300, PassMath.levelPrice(PASS, 0, 0, 3));
        assertEquals(-1, PassMath.levelPrice(PASS, 48_000, 0, 3));
        assertEquals(200, PassMath.levelPrice(PASS, 48_000, 0, 2));
        assertEquals(-1, PassMath.levelPrice(PASS, 0, 0, 0));
    }

    @Test
    void premiumPricesAndPlusBonus() {
        assertEquals(990, PassMath.premiumPrice(PASS, 0, 1));
        assertEquals(1990, PassMath.premiumPrice(PASS, 0, 2));
        assertEquals(1000, PassMath.premiumPrice(PASS, 1, 2));
        assertEquals(-1, PassMath.premiumPrice(PASS, 2, 1));
        assertEquals(-1, PassMath.premiumPrice(PASS, 1, 1));
        assertEquals(10, PassMath.premiumPlusBonus(PASS, 0, 2));
        assertEquals(10, PassMath.premiumPlusBonus(PASS, 1, 2));
        assertEquals(0, PassMath.premiumPlusBonus(PASS, 0, 1));
    }

    @Test
    void latePremiumClaimsAllReachedPremiumRewardsOnce() {
        // reach level 6 on free and claim the two free rewards
        Set<String> claimed = Set.of("1:free", "5:free");
        assertTrue(PassMath.claimable(PASS, 6, PassMath.FREE, claimed).isEmpty());
        // buying premium late grants the already-reached level 1 and 5 premium rewards at once
        // (level 20 not yet)
        List<PassDef.Reward> late = PassMath.claimable(PASS, 6, PassMath.PREMIUM, claimed);
        assertEquals(2, late.size());
        assertTrue(
                late.stream().allMatch(r -> r.track() == PassDef.Track.PREMIUM && r.level() <= 6));
        // premium+ includes the border
        assertEquals(3, PassMath.claimable(PASS, 6, PassMath.PREMIUM_PLUS, claimed).size());
        assertEquals("1:premium_plus", PassMath.claimKey(PASS.rewards().get(2)));
        assertEquals(
                "catch.legendary",
                PassMath.catchSource(wiki.creeper.harbor.common.model.Grade.LEGENDARY));
    }
}
