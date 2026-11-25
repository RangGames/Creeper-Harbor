package wiki.creeper.harbor.fishing.roll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.content.RodSettings;

/** Fish tags and conditional bait bonuses, special spawn conditions, rod enchant prices. */
class RodBaitRulesTest {

    private static final RegionId FJORD = RegionId.parse("region/frost_fjord");

    private static FishDef fish(
            String name, Grade grade, int weight, List<String> tags, List<FishDef.Extra> extra) {
        return new FishDef(
                FishId.parse("fish/" + name),
                1,
                name,
                List.of(),
                grade,
                FJORD,
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
                weight,
                false,
                "harbor:fish/" + name,
                1,
                tags,
                extra);
    }

    private static RollContext ctx(String time, Set<String> flags, Set<String> equipment) {
        return new RollContext(
                FJORD,
                time,
                "clear",
                "",
                null,
                3,
                flag -> false,
                fish -> true,
                List.of(),
                false,
                List.of(),
                flags::contains,
                equipment);
    }

    private static RollModifiers mods(Map<String, Double> stats) {
        List<RollModifiers.WeightBonus> bonus =
                stats.entrySet().stream()
                        .flatMap(
                                e ->
                                        RollModifiers.WeightBonus.parse(e.getKey(), e.getValue())
                                                .stream())
                        .toList();
        return new RollModifiers(Map.of(), 0, 0, 1, Map.of(), 0, bonus);
    }

    @Test
    void weightBonusMatchesTagsTimeAndRegion() {
        FishDef carnivore = fish("pike", Grade.RARE, 100, List.of("sea", "carnivore"), List.of());
        FishDef grazer = fish("cod", Grade.RARE, 100, List.of("sea"), List.of());
        RollModifiers lure = mods(Map.of("weight_bonus.carnivore+day", 0.15));
        assertEquals(0.15, RollEngine.weightBonus(carnivore, ctx("day", Set.of(), Set.of()), lure));
        assertEquals(
                0.0, RollEngine.weightBonus(carnivore, ctx("night", Set.of(), Set.of()), lure));
        assertEquals(0.0, RollEngine.weightBonus(grazer, ctx("day", Set.of(), Set.of()), lure));
        // Half of the grade's weight matches → half of the bonus at grade level
        assertEquals(
                0.075,
                RollEngine.gradeShare(
                        List.of(carnivore, grazer), ctx("day", Set.of(), Set.of()), lure),
                1e-9);

        RollModifiers iceWorm = mods(Map.of("weight_bonus.frost_fjord", 0.15));
        assertEquals(
                0.15,
                RollEngine.gradeShare(
                        List.of(carnivore, grazer), ctx("night", Set.of(), Set.of()), iceWorm),
                1e-9,
                "지역 전체에 맞으면 등급 보정 전부");
        RollModifiers shrimp = mods(Map.of("weight_bonus.sea", 0.10, "rare_weight", 0.05));
        assertEquals(1, shrimp.weightBonus().size(), "weight_bonus 키만");
        assertTrue(RollModifiers.WeightBonus.parse("weight_bonus.", 1).isEmpty());
    }

    @Test
    void extraConditionsNeedPlayerFlagServerFlagAndEquipment() {
        FishDef greed =
                fish(
                        "greed",
                        Grade.LEGENDARY,
                        100,
                        List.of(),
                        List.of(new FishDef.Extra(FishDef.Extra.Type.EQUIPMENT_TAG, "gold")));
        FishDef dragon =
                fish(
                        "dragon",
                        Grade.LEGENDARY,
                        100,
                        List.of(),
                        List.of(
                                new FishDef.Extra(
                                        FishDef.Extra.Type.PLAYER_FLAG, "boss_clear/void_nebula")));
        assertFalse(RollEngine.eligible(greed, ctx("day", Set.of(), Set.of("iron"))));
        assertTrue(RollEngine.eligible(greed, ctx("day", Set.of(), Set.of("gold", "iron"))));
        assertFalse(RollEngine.eligible(dragon, ctx("day", Set.of(), Set.of())));
        assertTrue(
                RollEngine.eligible(
                        dragon, ctx("day", Set.of("boss_clear/void_nebula"), Set.of())));
    }

    @Test
    void conditionalStatsCountOnlyWhenAllTokensMatch() {
        Map<String, Double> gear =
                Map.of("bite_wait", -0.1, "bite_wait.night", -0.1, "bite_wait.night+swamp", -0.5);
        assertEquals(
                -0.1, ConditionalStats.sum(gear, "bite_wait", Set.of("night", "harbor")), 1e-9);
        assertEquals(0.0, ConditionalStats.sum(gear, "bite_wait", Set.of("day")), 1e-9);
        assertEquals(-0.6, ConditionalStats.sum(gear, "bite_wait", Set.of("night", "swamp")), 1e-9);
    }

    @Test
    void enchantUpgradePaysTheDifference() {
        RodSettings.Enchant tide =
                new RodSettings.Enchant(
                        "tide_reading",
                        "bite_wait",
                        List.of(-0.05, -0.10, -0.15),
                        List.of(1500L, 4000L, 8000L));
        assertEquals(1500, tide.upgradePrice(0, 1));
        assertEquals(4000, tide.upgradePrice(0, 2), "II를 바로 사면 4,000");
        assertEquals(2500, tide.upgradePrice(1, 2));
        assertEquals(-0.10, tide.value(2), 1e-9);
        assertEquals(0, tide.value(0), 1e-9);
        assertEquals(3, tide.maxLevel());
    }

    @Test
    void baitSaveIsJudgedAtHook() {
        assertFalse(BaitRule.consumeOnHook(false, 0.01, 0.08));
        assertTrue(BaitRule.consumeOnHook(false, 0.5, 0.08));
        assertFalse(BaitRule.refund(wiki.creeper.harbor.fishing.api.MinigameResult.ABORTED, false));
    }
}
