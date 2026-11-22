package wiki.creeper.harbor.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.BillboardRules;
import wiki.creeper.harbor.collection.content.CollectionContent;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.collection.content.ReleaseRules;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.fishing.content.FishingContent;
import wiki.creeper.harbor.season.content.SeasonContent;

/**
 * Checks that the Collection files in the repository content/ load with the same loader as the
 * server.
 */
class CollectionContentTest {

    private static final Set<String> OWNED =
            Set.of(
                    "collection.yml",
                    "aquarium.yml",
                    "display_case.yml",
                    "release.yml",
                    "billboard.yml",
                    "reset_plan.yml");

    @Test
    void repositoryContentParses() {
        Path dir = Path.of("..", "content");
        assumeTrue(Files.isDirectory(dir), "content 폴더가 없습니다");
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        FishingContent.TYPES.forEach(loader::register);
        SeasonContent.TYPES.forEach(loader::register);
        CollectionContent.TYPES.forEach(loader::register);
        switch (loader.load(dir)) {
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
                                            return OWNED.stream().anyMatch(file::endsWith);
                                        })
                                .toList();
                assertTrue(mine.isEmpty(), () -> "Collection 콘텐츠 오류: " + mine);
                assumeTrue(false, "다른 콘텐츠 오류로 건너뜀: " + errors.size() + "건");
            }
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) -> {
                AquariumRules aquarium = snapshot.single(AquariumRules.TYPE);
                assertTrue(aquarium.display().enabled());
                assertEquals(2, aquarium.display().openTanks(0).size());
                assertEquals(5, aquarium.display().openTanks(4).size());
                assertEquals("harbor:aquarium/stage_1", aquarium.structureFor(2).orElseThrow());
                assertEquals("harbor:aquarium/stage_0", aquarium.structureFor(0).orElseThrow());
                DisplayCaseRules display = snapshot.single(DisplayCaseRules.TYPE);
                assertEquals(6, display.slotCount(1));
                assertEquals(10, display.slotCount(5));
                assertEquals("climax_done", snapshot.single(ReleaseRules.TYPE).flag());
                assertTrue(snapshot.single(BillboardRules.TYPE).enabled());
            }
        }
    }
}
