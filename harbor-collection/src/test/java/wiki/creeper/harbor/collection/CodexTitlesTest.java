package wiki.creeper.harbor.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.content.CodexRules;
import wiki.creeper.harbor.collection.content.CodexTitles;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Codex title key → Season TitleId, reference ID, and pending queue (earlier achievements)
 * computation.
 */
class CodexTitlesTest {

    private static final CodexRules RULES =
            new CodexRules(
                    Grade.RARE,
                    1,
                    3,
                    "codex_region",
                    List.of(
                            new CodexRules.Milestone(25, "codex_25"),
                            new CodexRules.Milestone(50, "codex_50"),
                            new CodexRules.Milestone(100, "codex_100")),
                    List.of());

    @Test
    void keysMapToSeasonTitleIds() {
        for (String key : List.of("codex_25", "codex_region/harbor", "title/codex_50")) {
            assertEquals(TitleId.of(key).id(), CodexTitles.titleId(key), key);
        }
        assertEquals("harbor:title/codex_25", CodexTitles.titleId("codex_25").toString());
        assertEquals(
                "harbor:title/codex_region/harbor",
                CodexTitles.titleId("codex_region/harbor").toString());
    }

    @Test
    void regionTitleUsesRegionName() {
        assertEquals(
                "codex_region/coral_isles",
                CodexTitles.regionTitle("codex_region", RegionId.parse("region/coral_isles")));
    }

    @Test
    void refIdFitsSeasonLimit() {
        assertEquals("codex:3:codex_25", CodexTitles.refId(3, "codex_25"));
        String longKey = "codex_region/" + "x".repeat(80);
        assertTrue(CodexTitles.refId(12, longKey).length() <= CodexTitles.REF_MAX);
    }

    @Test
    void earnedTitlesFromCurrentCodex() {
        RegionId harbor = RegionId.parse("region/harbor");
        RegionId swamp = RegionId.parse("region/swamp");
        FishId a = FishId.parse("fish/a");
        FishId b = FishId.parse("fish/b");
        FishId c = FishId.parse("fish/c");
        Map<RegionId, Set<FishId>> regions = Map.of(harbor, Set.of(a, b), swamp, Set.of(c));
        assertEquals(
                List.of("codex_25", "codex_50", "codex_region/harbor"),
                CodexTitles.earned(RULES, 66.7, regions, Set.of(a, b)));
        assertEquals(List.of(), CodexTitles.earned(RULES, 10, regions, Set.of(a)));
        assertEquals(
                List.of(
                        "codex_25",
                        "codex_50",
                        "codex_100",
                        "codex_region/harbor",
                        "codex_region/swamp"),
                CodexTitles.earned(RULES, 100, regions, Set.of(a, b, c)));
    }
}
