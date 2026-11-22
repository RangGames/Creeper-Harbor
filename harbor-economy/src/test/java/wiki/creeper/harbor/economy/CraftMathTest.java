package wiki.creeper.harbor.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.economy.content.RecipeDef;
import wiki.creeper.harbor.economy.service.CraftMath;

class CraftMathTest {

    private static final RegionId HARBOR = RegionId.parse("region/harbor");

    private static FishDef def(String id, Grade grade) {
        return new FishDef(
                FishId.parse("fish/" + id),
                1,
                id,
                List.of(),
                grade,
                HARBOR,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                null,
                10,
                20,
                5,
                100,
                false,
                "harbor:fish/x",
                1);
    }

    private static final Map<FishId, FishDef> FISH =
            Map.of(
                    FishId.parse("fish/mackerel"), def("mackerel", Grade.COMMON),
                    FishId.parse("fish/snapper"), def("snapper", Grade.UNCOMMON),
                    FishId.parse("fish/eel"), def("eel", Grade.RARE));

    private static Optional<FishDef> lookup(FishId id) {
        return Optional.ofNullable(FISH.get(id));
    }

    private static HarborItem.Fish fish(String id) {
        return new HarborItem.Fish(
                FishId.parse("fish/" + id),
                12.3,
                Quality.of(2),
                new PlayerRef(UUID.randomUUID(), "tester"),
                new SeasonId(1),
                Instant.EPOCH,
                null,
                1);
    }

    private static RecipeDef recipe(RecipeDef.Category category, List<RecipeDef.Ingredient> in) {
        return new RecipeDef(
                HarborId.parse("recipe/test"),
                ItemId.parse("item/food/grilled_fish"),
                1,
                RecipeDef.Station.COOKING,
                category,
                0,
                in,
                RecipeDef.Unlock.NONE,
                0);
    }

    @Test
    void materialSaveOnlyForGear() {
        // Artisan's Hand rank 2 (0.2): ceil(8 × 0.8) = 7 (6.4 → 7)
        assertEquals(7, CraftMath.materialCount(8, RecipeDef.Category.GEAR, 0.2, 0.5));
        assertEquals(8, CraftMath.materialCount(8, RecipeDef.Category.BAIT, 0.2, 0.5));
        // Cap 0.5, minimum 1
        assertEquals(4, CraftMath.materialCount(8, RecipeDef.Category.GEAR, 0.9, 0.5));
        assertEquals(1, CraftMath.materialCount(1, RecipeDef.Category.GEAR, 0.3, 0.5));
    }

    @Test
    void baitArtisanAddsResults() {
        assertEquals(12, CraftMath.resultAmount(10, RecipeDef.Category.BAIT, 2));
        assertEquals(1, CraftMath.resultAmount(1, RecipeDef.Category.GEAR, 2));
    }

    @Test
    void narrowIngredientsAreAllocatedFirst() {
        // Sashimi platter: 1 rare or higher + 2 common or higher. Even with slot order [rare,
        // common], the broad condition does not take the rare first
        RecipeDef sashimi =
                recipe(
                        RecipeDef.Category.FOOD,
                        List.of(
                                new RecipeDef.FishMatch(Grade.COMMON, List.of(), null, 2),
                                new RecipeDef.FishMatch(Grade.RARE, List.of(), null, 1)));
        List<CraftMath.Slot> slots =
                List.of(
                        new CraftMath.Slot(0, fish("eel"), null, 1),
                        new CraftMath.Slot(1, fish("mackerel"), null, 2));
        Map<Integer, Integer> plan =
                CraftMath.allocate(CraftMath.needs(sashimi, 0, 0.5), slots, CraftMathTest::lookup)
                        .orElseThrow();
        assertEquals(Map.of(0, 1, 1, 2), plan);
        // Fails if one common is missing
        List<CraftMath.Slot> short_ =
                List.of(
                        new CraftMath.Slot(0, fish("eel"), null, 1),
                        new CraftMath.Slot(1, fish("mackerel"), null, 1));
        assertTrue(
                CraftMath.allocate(CraftMath.needs(sashimi, 0, 0.5), short_, CraftMathTest::lookup)
                        .isEmpty());
    }

    @Test
    void itemVanillaAndFishAnyMatch() {
        RecipeDef stew =
                recipe(
                        RecipeDef.Category.FOOD,
                        List.of(
                                new RecipeDef.FishMatch(
                                        null, List.of(FishId.parse("fish/snapper")), null, 1),
                                new RecipeDef.Vanilla("minecraft:potato", 1),
                                new RecipeDef.Item(ItemId.parse("item/bait/earthworm"), 3)));
        List<CraftMath.Slot> slots =
                List.of(
                        new CraftMath.Slot(0, fish("mackerel"), null, 5),
                        new CraftMath.Slot(1, fish("snapper"), null, 1),
                        new CraftMath.Slot(2, null, "minecraft:potato", 4),
                        new CraftMath.Slot(
                                3,
                                new HarborItem.Plain(ItemId.parse("item/bait/earthworm"), 1),
                                null,
                                2),
                        new CraftMath.Slot(
                                4,
                                new HarborItem.Plain(ItemId.parse("item/bait/earthworm"), 1),
                                null,
                                5));
        Map<Integer, Integer> plan =
                CraftMath.allocate(CraftMath.needs(stew, 0, 0.5), slots, CraftMathTest::lookup)
                        .orElseThrow();
        assertEquals(Map.of(1, 1, 2, 1, 3, 2, 4, 1), plan);
    }

    @Test
    void regionMatchUsesFishDefinition() {
        RecipeDef.FishMatch fjord =
                new RecipeDef.FishMatch(null, List.of(), RegionId.parse("region/frost_fjord"), 1);
        assertTrue(
                !CraftMath.matches(
                        fjord, new CraftMath.Slot(0, fish("eel"), null, 1), CraftMathTest::lookup));
        RecipeDef.FishMatch harbor = new RecipeDef.FishMatch(null, List.of(), HARBOR, 1);
        assertTrue(
                CraftMath.matches(
                        harbor,
                        new CraftMath.Slot(0, fish("eel"), null, 1),
                        CraftMathTest::lookup));
    }
}
