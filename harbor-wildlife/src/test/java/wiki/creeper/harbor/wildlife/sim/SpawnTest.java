package wiki.creeper.harbor.wildlife.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.WildTemplate;

/** Natural spawn species selection and spot finding ('17-3' 9.4). */
class SpawnTest {

    private static SpawnTable.Conditions cond(
            String time, String weather, List<String> areas, Set<Medium> mediums, double depth) {
        return new SpawnTable.Conditions(
                Fixtures.HARBOR, time, weather, "", areas, f -> false, mediums, depth);
    }

    @Test
    void eligibilityFollowsFishConditions() {
        SpeciesProfile day =
                Fixtures.profile(
                        Fixtures.fish("goby", Grade.COMMON, List.of("day"), List.of(), List.of()),
                        Medium.WATER,
                        WildTemplate.FISH);
        SpeciesProfile rain =
                Fixtures.profile(
                        Fixtures.fish(
                                "bass", Grade.UNCOMMON, List.of(), List.of("rain"), List.of()),
                        Medium.WATER,
                        WildTemplate.FISH);
        SpeciesProfile light =
                Fixtures.profile(
                        Fixtures.fish(
                                "hairtail",
                                Grade.UNCOMMON,
                                List.of(),
                                List.of(),
                                List.of("lighthouse")),
                        Medium.WATER,
                        WildTemplate.FISH);
        SpeciesProfile legend =
                Fixtures.profile(
                        Fixtures.fish("king", Grade.LEGENDARY, List.of(), List.of(), List.of()),
                        Medium.WATER,
                        WildTemplate.FISH);
        Set<Medium> water = Set.of(Medium.WATER);

        assertTrue(SpawnTable.eligible(day, cond("day", "clear", List.of(), water, 3)));
        assertFalse(SpawnTable.eligible(day, cond("night", "clear", List.of(), water, 3)));
        assertTrue(
                SpawnTable.eligible(rain, cond("day", "thunder", List.of(), water, 3)),
                "뇌우는 비 조건도 만족");
        assertFalse(SpawnTable.eligible(rain, cond("day", "clear", List.of(), water, 3)));
        assertTrue(
                SpawnTable.eligible(light, cond("day", "clear", List.of("lighthouse"), water, 3)));
        assertFalse(SpawnTable.eligible(light, cond("day", "clear", List.of(), water, 3)));
        assertFalse(
                SpawnTable.eligible(legend, cond("day", "clear", List.of(), water, 3)),
                "전설은 자연 스폰 없음");
        assertFalse(
                SpawnTable.eligible(day, cond("day", "clear", List.of(), Set.of(Medium.LAVA), 3)),
                "매질");
    }

    @Test
    void depthBandFilters() {
        SpeciesProfile base = Fixtures.water("deep");
        SpeciesProfile deep =
                new SpeciesProfile(
                        base.species(),
                        base.fish(),
                        base.template(),
                        base.medium(),
                        base.group(),
                        base.groupDef(),
                        base.special(),
                        base.threatAction(),
                        base.actions(),
                        base.animations(),
                        base.swimAnimation(),
                        base.idleAnimation(),
                        base.biteAnimation(),
                        base.cruiseSpeed(),
                        base.turnRateDeg(),
                        base.pitchLimitDeg(),
                        4,
                        10,
                        base.schoolMin(),
                        base.schoolMax(),
                        base.threatRadius(),
                        base.spawnWeight(),
                        base.rig(),
                        base.parts(),
                        base.length(),
                        base.radius(),
                        base.scale(),
                        base.light(),
                        base.enabled());
        assertFalse(
                SpawnTable.eligible(
                        deep, cond("day", "clear", List.of(), Set.of(Medium.WATER), 2)));
        assertTrue(
                SpawnTable.eligible(
                        deep, cond("day", "clear", List.of(), Set.of(Medium.WATER), 6)));
        assertFalse(
                SpawnTable.eligible(
                        deep, cond("day", "clear", List.of(), Set.of(Medium.WATER), 12)));
    }

    @Test
    void weightedPickFollowsGradeWeightAndBoost() {
        SpeciesProfile common =
                Fixtures.profile(Fixtures.fish("a"), Medium.WATER, WildTemplate.FISH);
        SpeciesProfile rare =
                Fixtures.profile(
                        Fixtures.fish("b", Grade.RARE, List.of(), List.of(), List.of()),
                        Medium.WATER,
                        WildTemplate.FISH);
        RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
        Map<HarborId, Integer> hits = new HashMap<>();
        SpawnTable.Conditions c = cond("day", "clear", List.of(), Set.of(Medium.WATER), 3);
        int n = 70_000;
        for (int i = 0; i < n; i++) {
            Optional<SpeciesProfile> p =
                    SpawnTable.pick(
                            List.of(common, rare), c, Fixtures.BAL::gradeWeight, f -> 1.0, r);
            hits.merge(p.orElseThrow().species(), 1, Integer::sum);
        }
        // 60 : 10
        assertEquals(60.0 / 70, hits.get(common.species()) / (double) n, 0.01);

        FishId boosted = rare.fish().id();
        hits.clear();
        for (int i = 0; i < n; i++) {
            Optional<SpeciesProfile> p =
                    SpawnTable.pick(
                            List.of(common, rare),
                            c,
                            Fixtures.BAL::gradeWeight,
                            f -> f.equals(boosted) ? 6.0 : 1.0,
                            r);
            hits.merge(p.orElseThrow().species(), 1, Integer::sum);
        }
        // 60 : 60
        assertEquals(0.5, hits.get(rare.species()) / (double) n, 0.01);
        assertTrue(SpawnTable.pick(List.of(), c, Fixtures.BAL::gradeWeight, f -> 1.0, r).isEmpty());
    }

    @Test
    void timeKeysMatchFishing() {
        assertEquals("dawn", SpawnTable.timeKey(23500));
        assertEquals("dawn", SpawnTable.timeKey(500));
        assertEquals("day", SpawnTable.timeKey(6000));
        assertEquals("dusk", SpawnTable.timeKey(12500));
        assertEquals("night", SpawnTable.timeKey(18000));
        assertEquals("day", SpawnTable.timeKey(24000 * 3 + 6000));
    }

    @Test
    void columnFindsDeepEnoughWater() {
        // water 50~62 (surface 63), solid floor below
        MediumField field = Fixtures.slab(MediumField.WATER, 50, 62);
        RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 50; i++) {
            SpawnPlanner.Spot s =
                    SpawnPlanner.column(field, Medium.WATER, 0, 0, 70, 40, 2, 3, r).orElseThrow();
            assertTrue(s.pos().y() >= 50 && s.pos().y() < 62, "수면 바로 아래 칸은 피함: " + s.pos());
            assertEquals(63 - s.pos().y(), s.depth(), 1e-9);
            assertEquals(50, s.floorY());
        }
        // a 1-cell puddle is rejected
        assertTrue(
                SpawnPlanner.column(
                                Fixtures.slab(MediumField.WATER, 60, 60),
                                Medium.WATER,
                                0,
                                0,
                                70,
                                40,
                                2,
                                3,
                                r)
                        .isEmpty());
        // floor fish sit right on the floor
        SpawnPlanner.Spot floor =
                SpawnPlanner.column(field, Medium.FLOOR, 0, 0, 70, 40, 2, 3, r).orElseThrow();
        assertEquals(50.15, floor.pos().y(), 1e-9);
    }

    @Test
    void columnForAirNeedsClearance() {
        MediumField air = Fixtures.slab(MediumField.AIR, 70, 71);
        RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
        assertTrue(SpawnPlanner.column(air, Medium.AIR, 0, 0, 90, 50, 2, 3, r).isEmpty());
        MediumField open = Fixtures.slab(MediumField.AIR, 70, 80);
        SpawnPlanner.Spot s =
                SpawnPlanner.column(open, Medium.AIR, 0, 0, 90, 50, 2, 3, r).orElseThrow();
        assertTrue(s.pos().y() >= 71 && s.pos().y() <= 80);
        assertEquals(0, s.depth());
    }

    @Test
    void ringAndGap() {
        RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 200; i++) {
            double[] p = SpawnPlanner.ringPoint(100, -50, 16, 40, r);
            double d = Math.hypot(p[0] - 100, p[1] + 50);
            assertTrue(d >= 16 - 1e-9 && d <= 40 + 1e-9);
        }
        List<Vec3> centers = List.of(new Vec3(0, 60, 0));
        assertFalse(SpawnPlanner.farFrom(new Vec3(5, 60, 0), centers, 8));
        assertTrue(SpawnPlanner.farFrom(new Vec3(9, 60, 0), centers, 8));
        for (int i = 0; i < 100; i++) {
            int s = SpawnPlanner.schoolSize(3, 6, r);
            assertTrue(s >= 3 && s <= 6);
        }
    }

    @Test
    void chunkCellsRoundTrip() {
        ChunkCells cells =
                ChunkCells.build(
                        0,
                        63,
                        MediumField.WATER | MediumField.AIR,
                        (x, y, z) ->
                                y <= 40
                                        ? MediumField.WATER
                                        : y <= 50 ? MediumField.LAVA : MediumField.AIR);
        assertEquals(MediumField.WATER, cells.cell(3, 20, 7));
        assertEquals(0, cells.cell(3, 45, 7), "마스크에 없는 용암은 담지 않음");
        assertEquals(MediumField.AIR, cells.cell(3, 60, 7));
        assertEquals(0, cells.cell(3, 64, 7), "범위 밖");
        ChunkCells changed = cells.withSection(1, 0, 63, MediumField.WATER, (x, y, z) -> 0);
        assertEquals(0, changed.cell(3, 20, 7));
        assertEquals(MediumField.WATER, cells.cell(3, 20, 7), "원본은 그대로 (변경 불가)");

        MediumGrid grid = new MediumGrid();
        grid.put("w", -1, -1, cells);
        MediumField f = grid.field("w");
        assertEquals(MediumField.WATER, f.cell(-5, 10, -12));
        assertEquals(0, f.cell(5, 10, 5), "적재 안 된 청크는 막힘");
    }
}
