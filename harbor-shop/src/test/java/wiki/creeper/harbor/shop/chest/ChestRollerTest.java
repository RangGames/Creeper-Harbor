package wiki.creeper.harbor.shop.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.shop.content.ChestDef;

class ChestRollerTest {

    private static final ChestDef CHEST =
            new ChestDef(
                    HarborId.parse("chest/test"),
                    "테스트",
                    2,
                    150,
                    1350,
                    1,
                    new ChestDef.Pity(50, Grade.LEGENDARY),
                    Map.of(),
                    Map.of(),
                    List.of(
                            new ChestDef.Entry(
                                    HarborId.parse("cosmetic/top"),
                                    Grade.LEGENDARY,
                                    15_000,
                                    "1.5000"),
                            new ChestDef.Entry(
                                    HarborId.parse("cosmetic/epic"), Grade.EPIC, 85_000, "8.5000"),
                            new ChestDef.Entry(
                                    HarborId.parse("cosmetic/rare"),
                                    Grade.RARE,
                                    300_000,
                                    "30.0000"),
                            new ChestDef.Entry(
                                    HarborId.parse("cosmetic/common"),
                                    Grade.COMMON,
                                    600_000,
                                    "60.0000")));

    @Test
    void cumulativeTablePicksByPpm() {
        assertEquals("cosmetic/top", ChestRoller.roll(CHEST, 0, () -> 0).entry().cosmetic().path());
        assertEquals(
                "cosmetic/top", ChestRoller.roll(CHEST, 0, () -> 14_999).entry().cosmetic().path());
        assertEquals(
                "cosmetic/epic",
                ChestRoller.roll(CHEST, 0, () -> 15_000).entry().cosmetic().path());
        assertEquals(
                "cosmetic/common",
                ChestRoller.roll(CHEST, 0, () -> 999_999).entry().cosmetic().path());
    }

    @Test
    void fiftiethPullIsGuaranteedTopGradeAndResetsCounter() {
        ChestRoller.Outcome miss = ChestRoller.roll(CHEST, 48, () -> 999_999);
        assertEquals(49, miss.pityAfter());
        ChestRoller.Outcome pity = ChestRoller.roll(CHEST, 49, () -> 999_999);
        assertTrue(pity.pityHit());
        assertEquals(Grade.LEGENDARY, pity.entry().grade());
        assertEquals(0, pity.pityAfter());
        ChestRoller.Outcome natural = ChestRoller.roll(CHEST, 10, () -> 5);
        assertEquals(0, natural.pityAfter());
    }

    @Test
    void neverMoreThanFiftyPullsWithoutTopAndCarriesCounter() {
        SplittableRandom random = new SplittableRandom(42);
        int pity = 0;
        int sinceTop = 0;
        for (int i = 0; i < 20_000; i++) {
            ChestRoller.Outcome o =
                    ChestRoller.roll(CHEST, pity, () -> random.nextInt(ChestDef.PPM_TOTAL));
            sinceTop++;
            if (o.top(CHEST)) {
                assertTrue(sinceTop <= 50, "천장 50회 보장: " + sinceTop);
                sinceTop = 0;
            }
            pity = o.pityAfter();
        }
    }

    @Test
    void simulatedRatesMatchPublishedTable() {
        SplittableRandom random = new SplittableRandom(7);
        Map<Grade, Integer> counts = new EnumMap<>(Grade.class);
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            // 천장 없이 표 자체만 (카운터 0 고정)
            counts.merge(
                    ChestRoller.roll(CHEST, 0, () -> random.nextInt(ChestDef.PPM_TOTAL))
                            .entry()
                            .grade(),
                    1,
                    Integer::sum);
        }
        for (ChestDef.Entry e : CHEST.entries()) {
            double expected = e.ppm() / (double) ChestDef.PPM_TOTAL;
            double actual = counts.getOrDefault(e.grade(), 0) / (double) n;
            double sigma = Math.sqrt(expected * (1 - expected) / n);
            assertTrue(
                    Math.abs(actual - expected) < 5 * sigma,
                    e.grade() + " " + actual + " vs " + expected);
        }
    }
}
