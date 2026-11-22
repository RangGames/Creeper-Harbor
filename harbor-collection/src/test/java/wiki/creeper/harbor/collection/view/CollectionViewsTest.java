package wiki.creeper.harbor.collection.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.CollectionRules;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

class CollectionViewsTest {

    static final RegionId HARBOR = new RegionId(HarborId.parse("region/harbor"));
    static final RegionId SWAMP = new RegionId(HarborId.parse("region/swamp"));

    static FishId fish(String id) {
        return new FishId(HarborId.parse("fish/" + id));
    }

    static CollectionView.Item item(String id, int no, Grade grade, RegionId region, Long caught) {
        return new CollectionView.Item(
                fish(id), no, grade, region, caught == null ? null : Instant.ofEpochSecond(caught));
    }

    static final List<CollectionView.Item> ITEMS =
            List.of(
                    item("a", 3, Grade.COMMON, HARBOR, 100L),
                    item("b", 1, Grade.RARE, HARBOR, null),
                    item("c", 2, Grade.EPIC, SWAMP, 300L),
                    item("d", 4, Grade.RARE, SWAMP, 200L));

    static List<Integer> nos(List<CollectionView.Item> items) {
        return items.stream().map(CollectionView.Item::no).toList();
    }

    @Test
    void collectionFiltersAndSorts() {
        CollectionView.State d = CollectionView.State.DEFAULT;
        assertEquals(List.of(1, 2, 3, 4), nos(CollectionView.apply(ITEMS, d)));
        assertEquals(List.of(1, 3), nos(CollectionView.apply(ITEMS, d.withRegion(HARBOR))));
        assertEquals(
                List.of(1),
                nos(CollectionView.apply(ITEMS, d.withFilter(CollectionView.Filter.UNREGISTERED))));
        assertEquals(List.of(1, 4), nos(CollectionView.apply(ITEMS, d.withGrade(Grade.RARE))));
        assertEquals(
                List.of(2, 1, 4, 3),
                nos(CollectionView.apply(ITEMS, d.withSort(CollectionView.Sort.GRADE))));
        assertEquals(
                List.of(2, 4, 3), nos(CollectionView.apply(ITEMS, CollectionView.State.RECENT)));
    }

    @Test
    void collectionHelpers() {
        assertEquals(Grade.COMMON, CollectionView.nextGrade(null));
        assertNull(CollectionView.nextGrade(Grade.MYTHIC));
        assertEquals(CollectionView.Filter.ALL, CollectionView.Filter.REGISTERED.next());
        assertEquals(1, CollectionView.pages(0, 36));
        assertEquals(2, CollectionView.pages(37, 36));
        assertEquals(50.0, CollectionView.percent(1, 2));
        assertEquals(0.0, CollectionView.percent(1, 0));
        assertEquals(5, CollectionView.filled(0.5, 10));
        assertEquals(10, CollectionView.filled(1.7, 10));
        assertEquals(0, CollectionView.filled(Double.NaN, 10));
        assertEquals(3, CollectionView.filled(0.3, 10), "부동소수 오차로 칸이 덜 차지 않는다");
    }

    @Test
    void milestoneTrackMarksReached() {
        CollectionRules rules =
                new CollectionRules(
                        Grade.RARE,
                        1,
                        3,
                        "codex_region",
                        List.of(
                                new CollectionRules.Milestone(50, "codex_50"),
                                new CollectionRules.Milestone(25, "codex_25")),
                        List.of(new CollectionRules.SeasonBuff(20, List.of())));
        List<MilestoneTrack.Step> steps = MilestoneTrack.steps(rules, 30, 10);
        assertEquals(3, steps.size());
        assertEquals(25, steps.get(0).percent());
        assertTrue(steps.get(0).reached());
        assertFalse(steps.get(1).reached());
        assertFalse(steps.get(2).reached());
        assertEquals(50, MilestoneTrack.next(steps, MilestoneTrack.Kind.TOTAL_TITLE).getAsInt());
        assertEquals(20, MilestoneTrack.next(steps, MilestoneTrack.Kind.SEASON_BUFF).getAsInt());
    }

    @Test
    void recordRanks() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<RecordEntry> top =
                List.of(
                        new RecordEntry(a, "A", 50, Instant.EPOCH),
                        new RecordEntry(b, "B", 40, Instant.EPOCH));
        assertEquals(2, RecordRanks.rank(top, b).getAsInt());
        assertTrue(RecordRanks.rank(top, c).isEmpty());
        assertEquals(0, RecordRanks.gapToTop(top, 10, 10).getAsDouble(), "목록이 덜 찼으면 0");
        assertTrue(RecordRanks.gapToTop(top, 39, 2).getAsDouble() > 1);
        assertEquals(3, RecordRanks.wouldRank(top, 40, 10).getAsInt(), "같은 크기는 뒤");
        assertEquals(1, RecordRanks.wouldRank(top, 60, 10).getAsInt());
        assertTrue(RecordRanks.wouldRank(top, 1, 2).isEmpty());
    }

    @Test
    void aquariumStagesAndSpecies() {
        AquariumRules rules =
                new AquariumRules(
                        Map.of(),
                        0,
                        1,
                        5,
                        List.of(
                                new AquariumRules.Stage(2, 0.25, "s2", List.of(), null),
                                new AquariumRules.Stage(1, 0.10, "s1", List.of(), null)),
                        AquariumRules.Display.NONE);
        List<AquariumView.StageRow> stages = AquariumView.stages(rules, 1, 40);
        assertEquals(1, stages.get(0).stage().stage());
        assertTrue(stages.get(0).reached());
        assertTrue(stages.get(1).next());
        assertEquals(10, stages.get(1).needed());
        assertEquals(4, AquariumView.remaining(stages, 6).orElseThrow());
        assertEquals(3, AquariumView.needed(0.07, 40), "올림");

        FishId x = fish("x");
        FishId y = fish("y");
        FishId z = fish("z");
        Map<FishId, Integer> donatable = new LinkedHashMap<>();
        donatable.put(x, 1);
        donatable.put(y, 2);
        donatable.put(z, 3);
        List<AquariumView.SpeciesRow> missing =
                AquariumView.species(
                        donatable, Set.of(x), Map.of(z, 7), AquariumView.Filter.MISSING);
        assertEquals(List.of(z, y), missing.stream().map(AquariumView.SpeciesRow::fish).toList());
        assertEquals(7, missing.getFirst().inventorySlot());
        assertEquals(
                List.of(x),
                AquariumView.species(donatable, Set.of(x), Map.of(), AquariumView.Filter.DONATED)
                        .stream()
                        .map(AquariumView.SpeciesRow::fish)
                        .toList());
    }
}
