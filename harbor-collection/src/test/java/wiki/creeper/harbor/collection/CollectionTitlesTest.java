package wiki.creeper.harbor.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.content.CollectionRules;
import wiki.creeper.harbor.collection.content.CollectionTitles;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Collection title key → Season TitleId, reference ID, and pending queue (earlier achievements)
 * computation.
 */
class CollectionTitlesTest {

    private static final CollectionRules RULES =
            new CollectionRules(
                    Grade.RARE,
                    1,
                    3,
                    "codex_region",
                    List.of(
                            new CollectionRules.Milestone(25, "codex_25"),
                            new CollectionRules.Milestone(50, "codex_50"),
                            new CollectionRules.Milestone(100, "codex_100")),
                    List.of());

    @Test
    void keysMapToSeasonTitleIds() {
        for (String key : List.of("codex_25", "codex_region/harbor", "title/codex_50")) {
            assertEquals(TitleId.of(key).id(), CollectionTitles.titleId(key), key);
        }
        assertEquals("harbor:title/codex_25", CollectionTitles.titleId("codex_25").toString());
        assertEquals(
                "harbor:title/codex_region/harbor",
                CollectionTitles.titleId("codex_region/harbor").toString());
    }

    @Test
    void regionTitleUsesRegionName() {
        assertEquals(
                "codex_region/coral_isles",
                CollectionTitles.regionTitle("codex_region", RegionId.parse("region/coral_isles")));
    }

    @Test
    void refIdFitsSeasonLimit() {
        assertEquals("codex:3:codex_25", CollectionTitles.refId(3, "codex_25"));
        String longKey = "codex_region/" + "x".repeat(80);
        assertTrue(CollectionTitles.refId(12, longKey).length() <= CollectionTitles.REF_MAX);
    }

    @Test
    void earnedTitlesFromCurrentCollection() {
        RegionId harbor = RegionId.parse("region/harbor");
        RegionId swamp = RegionId.parse("region/swamp");
        FishId a = FishId.parse("fish/a");
        FishId b = FishId.parse("fish/b");
        FishId c = FishId.parse("fish/c");
        Map<RegionId, Set<FishId>> regions = Map.of(harbor, Set.of(a, b), swamp, Set.of(c));
        assertEquals(
                List.of("codex_25", "codex_50", "codex_region/harbor"),
                CollectionTitles.earned(RULES, 66.7, regions, Set.of(a, b)));
        assertEquals(List.of(), CollectionTitles.earned(RULES, 10, regions, Set.of(a)));
        assertEquals(
                List.of(
                        "codex_25",
                        "codex_50",
                        "codex_100",
                        "codex_region/harbor",
                        "codex_region/swamp"),
                CollectionTitles.earned(RULES, 100, regions, Set.of(a, b, c)));
    }
}
