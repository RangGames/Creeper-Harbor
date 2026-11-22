package wiki.creeper.harbor.fishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.roll.QualityRoller;
import wiki.creeper.harbor.fishing.session.Minigame;

class FishingLogicTest {

    private static final FishingBalance BALANCE = new FishingBalance(Balance.of(Map.of()));
    private static final double[] BOUNDS = BALANCE.qualityBounds();

    private static FishDef fish(Grade grade) {
        return new FishDef(
                FishId.parse("fish/test"),
                1,
                "테스트",
                List.of(),
                grade,
                RegionId.parse("region/harbor"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                null,
                20,
                40,
                10,
                100,
                false,
                "harbor:fish/test",
                1);
    }

    @Test
    void qualityIsCappedByJobTier() {
        SplittableRandom random = new SplittableRandom(1);
        int capped = 0;
        for (int i = 0; i < 10_000; i++) {
            QualityRoller.Rolled r =
                    QualityRoller.roll(fish(Grade.COMMON), 0, Quality.of(3), BOUNDS, random);
            assertTrue(r.quality().stars() <= 3);
            assertTrue(r.sizeCm() >= 20 && r.sizeCm() <= 40);
            if (r.capped()) {
                capped++;
                // Just below the top of the cap quality's range (0.90 × 20cm range)
                assertEquals(38.0, r.sizeCm(), 0.05);
            }
        }
        // ★4 or higher chance 10%
        assertTrue(capped > 800 && capped < 1200, "capped=" + capped);
    }

    @Test
    void luckRaisesAverageSize() {
        SplittableRandom random = new SplittableRandom(2);
        double plain = 0;
        double lucky = 0;
        for (int i = 0; i < 20_000; i++) {
            plain +=
                    QualityRoller.roll(fish(Grade.COMMON), 0, Quality.of(5), BOUNDS, random)
                            .sizeCm();
            lucky +=
                    QualityRoller.roll(fish(Grade.COMMON), 0.1, Quality.of(5), BOUNDS, random)
                            .sizeCm();
        }
        assertTrue(lucky > plain);
    }

    private static MinigameResult play(
            Grade grade, java.util.function.Function<Minigame, Boolean> strategy) {
        Minigame game =
                new Minigame(
                        BALANCE.minigame(grade),
                        new Minigame.Gear(0, 1, 1, 0, 12),
                        0.08,
                        new SplittableRandom(3));
        for (int t = 0; t < 20 * 120; t++) {
            boolean reel = strategy.apply(game);
            MinigameResult result = game.tick(new Minigame.Input(reel, false, false));
            if (result != null) {
                return result;
            }
        }
        return MinigameResult.ABORTED;
    }

    @Test
    void keepingTensionInSafeZoneCatchesCommonFish() {
        MinigameResult result =
                play(Grade.COMMON, g -> g.tension() < (g.safeLow() + g.safeHigh()) / 2);
        assertEquals(MinigameResult.CAUGHT, result);
    }

    @Test
    void alwaysReelingBreaksTheLine() {
        assertEquals(MinigameResult.LINE_BROKE, play(Grade.RARE, g -> true));
    }

    @Test
    void neverReelingLetsTheFishEscape() {
        assertEquals(MinigameResult.ESCAPED, play(Grade.COMMON, g -> false));
    }
}
