package wiki.creeper.harbor.fishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.fishing.content.FishingContent;
import wiki.creeper.harbor.fishing.content.FishingPresets;
import wiki.creeper.harbor.fishing.content.FoodBuffs;
import wiki.creeper.harbor.fishing.content.JunkTable;
import wiki.creeper.harbor.fishing.content.SeaEventPresets;
import wiki.creeper.harbor.fishing.trap.TrapMath;

/**
 * Reads the repository content/ with the same loader as the server (Core types + Fishing types) and
 * checks that the files Fishing uses have no errors. Errors in files other modules are working on
 * are not this test's concern, so only errors located in Fishing files count as failures.
 */
class FishingContentTest {

    private static final Set<String> OWNED =
            Set.of(
                    "fishing/",
                    "items/traps.yml",
                    "items/food.yml",
                    "items/fishing_materials.yml",
                    "items/fishing_bait.yml",
                    "fish/mythic.yml");

    @Test
    void fishingContentParses() {
        Path dir = Path.of("..", "content");
        assumeTrue(Files.isDirectory(dir), "content 폴더가 없습니다");
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        FishingContent.TYPES.forEach(loader::register);
        Result<ContentSnapshot, ValidationErrors> result = loader.load(dir);
        switch (result) {
            case Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors) -> {
                List<ValidationError> mine =
                        errors.list().stream()
                                .filter(
                                        e -> {
                                            var pos = e.pos();
                                            String file =
                                                    pos == null
                                                            ? ""
                                                            : pos.file().replace('\\', '/');
                                            return OWNED.stream().anyMatch(file::contains);
                                        })
                                .toList();
                assertTrue(mine.isEmpty(), () -> "Fishing 콘텐츠 오류: " + mine);
                // If the snapshot cannot be built because of errors in other modules' files, skip
                // the remaining checks
                assumeTrue(false, "다른 콘텐츠 오류로 건너뜀: " + errors.size() + "건");
            }
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) -> {
                JunkTable junk = snapshot.single(JunkTable.TYPE);
                assertEquals(
                        0.05,
                        junk.entries(RegionId.parse("region/coral_isles")).getFirst().chance(),
                        1e-9);
                FoodBuffs food = snapshot.single(FoodBuffs.TYPE);
                assertTrue(food.of(ItemId.parse("item/food/sashimi_platter")).isPresent());
                assertFalse(food.of(ItemId.parse("item/food/grilled_fish")).isPresent());
                assertTrue(
                        snapshot.single(SeaEventPresets.TYPE).get("great_migration").isPresent());
                FishingPresets presets = snapshot.single(FishingPresets.TYPE);
                assertFalse(presets.shows(0, "darkness"));
                assertTrue(presets.shows(2, "darkness"));
                ItemDef coral =
                        snapshot.get(ItemDef.TYPE, ItemId.parse("item/trap/coral").id())
                                .orElseThrow();
                assertEquals(9, TrapMath.Spec.of(coral.stats()).slots());
            }
        }
    }
}
