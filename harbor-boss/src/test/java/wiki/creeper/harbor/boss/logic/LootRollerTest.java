package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;

/** Loot rolling and raid pity (docs 20 10.4). */
class LootRollerTest {

    private static final ItemId LINE = new ItemId(HarborId.parse("item/line/lord_scale"));
    private static final ItemId SCALE = new ItemId(HarborId.parse("item/material/boss_scale"));

    @Test
    void sameSeedSameResult() {
        List<BossDef.Drop> drops =
                List.of(
                        new BossDef.Drop(SCALE, 0.5, 1, 3, false),
                        new BossDef.Drop(LINE, 0.1, 1, 1, true));
        long seed = LootRoller.seed(1234, new UUID(5, 6));
        var a = LootRoller.roll(drops, new SplittableRandom(seed), 0, Map.of(), 8);
        var b = LootRoller.roll(drops, new SplittableRandom(seed), 0, Map.of(), 8);
        assertEquals(a, b);
    }

    @Test
    void guaranteedDropAndCountRange() {
        List<BossDef.Drop> drops = List.of(new BossDef.Drop(SCALE, 1.0, 2, 3, false));
        for (int i = 0; i < 200; i++) {
            var r = LootRoller.roll(drops, new SplittableRandom(i), 0, Map.of(), 0);
            assertEquals(1, r.items().size());
            int n = r.items().getFirst().count();
            assertTrue(n >= 2 && n <= 3);
            assertTrue(r.pity().isEmpty());
        }
    }

    @Test
    void pityGuaranteesOnEighthAttemptAndResets() {
        // Drop with a very low chance: guaranteed on the 8th attempt
        List<BossDef.Drop> drops = List.of(new BossDef.Drop(LINE, 0.000001, 1, 1, true));
        Map<ItemId, Integer> pity = new HashMap<>();
        for (int attempt = 1; attempt <= 7; attempt++) {
            var r = LootRoller.roll(drops, new SplittableRandom(attempt), 0, pity, 8);
            assertTrue(r.items().isEmpty());
            pity = new HashMap<>(r.pity());
            assertEquals(attempt, pity.get(LINE));
        }
        var eighth = LootRoller.roll(drops, new SplittableRandom(99), 0, pity, 8);
        assertEquals(1, eighth.items().size());
        assertTrue(eighth.items().getFirst().byPity());
        assertEquals(0, eighth.pity().get(LINE));
    }

    @Test
    void bonusRaisesChance() {
        List<BossDef.Drop> drops = List.of(new BossDef.Drop(SCALE, 0.0001, 1, 1, false));
        var r = LootRoller.roll(drops, new SplittableRandom(1), 1.0, Map.of(), 0);
        assertEquals(1, r.items().size());
    }

    @Test
    void goldRange() {
        assertEquals(300, LootRoller.gold(300, 300, new SplittableRandom(1)));
        for (int i = 0; i < 100; i++) {
            long g = LootRoller.gold(100, 200, new SplittableRandom(i));
            assertTrue(g >= 100 && g <= 200);
        }
    }
}
