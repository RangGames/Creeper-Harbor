package wiki.creeper.harbor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.SeaEventType;
import wiki.creeper.harbor.world.api.UnlockStatus;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.api.WorldFlags;
import wiki.creeper.harbor.world.chart.ChartBits;
import wiki.creeper.harbor.world.chart.ChartMask;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.content.TreasureTable;
import wiki.creeper.harbor.world.geo.Polygon;
import wiki.creeper.harbor.world.geo.RegionIndex;
import wiki.creeper.harbor.world.housing.PlotGrid;
import wiki.creeper.harbor.world.region.UnlockRules;
import wiki.creeper.harbor.world.sea.EventPlanner;
import wiki.creeper.harbor.world.ship.ShipMath;

/**
 * Pure World logic: region shapes and index, entry conditions, event schedule, plot layout, chart
 * bitmap, ship stats, treasure rewards.
 */
class WorldLogicTest {

    private static List<RegionDef.Point> pts(double... xz) {
        java.util.ArrayList<RegionDef.Point> list = new java.util.ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            list.add(new RegionDef.Point(xz[i], xz[i + 1]));
        }
        return list;
    }

    // ------------------------------------------------------------------ shapes and index

    @Test
    void polygonContainsAndCover() {
        Polygon square = new Polygon(pts(0, 0, 100, 0, 100, 100, 0, 100));
        assertTrue(square.contains(50, 50));
        assertFalse(square.contains(150, 50));
        assertEquals(Polygon.Cover.INSIDE, square.cover(16, 16, 16));
        assertEquals(Polygon.Cover.OUTSIDE, square.cover(200, 200, 16));
        assertEquals(Polygon.Cover.PARTIAL, square.cover(96, 16, 16));
        assertEquals(10, square.edgeDistance(50, 10), 1e-9);
        double[] edge = square.nearestEdgePoint(50, 10);
        assertEquals(50, edge[0], 1e-9);
        assertEquals(0, edge[1], 1e-9);
        // Concave polygon (U shape): the concave part is outside
        Polygon u = new Polygon(pts(0, 0, 90, 0, 90, 30, 30, 30, 30, 60, 90, 60, 90, 90, 0, 90));
        assertTrue(u.contains(10, 45));
        assertFalse(u.contains(60, 45));
        assertEquals(Polygon.Cover.PARTIAL, u.cover(24, 24, 16));
        assertEquals(Polygon.Cover.OUTSIDE, u.cover(32, 32, 16));
    }

    @Test
    void indexMatchesBruteForce() {
        Polygon a = new Polygon(pts(-500, -500, 500, -500, 500, 500, -500, 500));
        Polygon b = new Polygon(pts(300, 300, 1200, 250, 1300, 900, 400, 1000));
        Polygon c = new Polygon(pts(-2000, -100, -1500, -900, -1000, 200));
        List<Polygon> polygons = List.of(a, b, c);
        RegionIndex index = RegionIndex.build(polygons);
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            double x = -2200 + random.nextDouble() * 3700;
            double z = -1100 + random.nextDouble() * 2300;
            int expected = RegionIndex.NONE;
            for (int r = 0; r < polygons.size(); r++) {
                if (polygons.get(r).contains(x, z)) {
                    expected = r;
                    break;
                }
            }
            assertEquals(expected, index.lookup(x, z), "(" + x + ", " + z + ")");
        }
        assertEquals(0, index.wholeChunk(0, 0));
        assertEquals(RegionIndex.NONE, index.wholeChunk(500 >> 4, 0));
        assertTrue(index.mixedChunks() > 0);
    }

    // ------------------------------------------------------------------ entry conditions

    private static RegionDef region(int level, List<ItemId> items, QuestId quest) {
        return new RegionDef(
                RegionId.parse("region/frost_fjord"),
                "서리 협만",
                "harbor_main",
                pts(0, 0, 10, 0, 10, 10),
                "harbor:frost_fjord",
                new RegionDef.Unlock(level, quest, items),
                12,
                50,
                List.of(),
                null);
    }

    private static RegionExt ext(RegionExt.Unlock unlock) {
        return new RegionExt(
                RegionId.parse("region/frost_fjord"),
                5,
                3200,
                null,
                null,
                unlock,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    @Test
    void unlockRules() {
        ItemId coat = ItemId.parse("item/gear/cold_coat");
        HarborId icebreaker = HarborId.parse("ship/icebreaker");
        QuestId quest = QuestId.parse("quest/main/ch4_frozen_bell");
        RegionDef def = region(40, List.of(coat), quest);
        RegionExt ext =
                ext(new RegionExt.Unlock(List.of("story/ch4_done"), 1, List.of(icebreaker), true));
        Set<String> flags = new HashSet<>();
        UnlockRules.Facts nothing =
                new UnlockRules.Facts(18, 1, flags::contains, i -> false, false, null);
        UnlockStatus status = UnlockRules.check(def, ext, nothing, i -> "외투", s -> "쇄빙선", false);
        assertFalse(status.allowed());
        assertEquals(5, status.missing().size());
        UnlockStatus.Requirement level = status.missing().getFirst();
        assertEquals(UnlockStatus.Kind.LEVEL, level.kind());
        assertEquals(18, level.have());
        assertEquals(40, level.need());

        flags.add(WorldFlags.questDone(quest));
        flags.add("story/ch4_done");
        UnlockRules.Facts ready =
                new UnlockRules.Facts(41, 2, flags::contains, coat::equals, false, null);
        UnlockStatus noShip = UnlockRules.check(def, ext, ready, i -> "외투", s -> "쇄빙선", false);
        assertTrue(noShip.onlyVehicleMissing());
        // The "highest unlocked region" check ignores the vehicle
        assertTrue(UnlockRules.check(def, ext, ready, i -> "", s -> "", true).allowed());
        // First entry is allowed while riding an icebreaker
        UnlockRules.Facts aboard =
                new UnlockRules.Facts(41, 2, flags::contains, coat::equals, false, icebreaker);
        assertTrue(UnlockRules.check(def, ext, aboard, i -> "", s -> "", false).allowed());
        // first_entry_only: once visited, no ship is needed (airship)
        UnlockRules.Facts visited =
                new UnlockRules.Facts(41, 2, flags::contains, coat::equals, true, null);
        assertTrue(UnlockRules.check(def, ext, visited, i -> "", s -> "", false).allowed());
        assertEquals("quest/main/ch4_frozen_bell", WorldFlags.questDone(quest));
        assertEquals(
                "visited/frost_fjord", WorldFlags.visited(RegionId.parse("region/frost_fjord")));
    }

    // ------------------------------------------------------------------ event schedule

    private static SeaEventDef event(
            double chance, int gapHours, int duration, Set<DayOfWeek> days, int maxPerSeason) {
        return new SeaEventDef(
                HarborId.parse("event/storm"),
                "폭풍",
                SeaEventType.STORM,
                List.of(RegionId.parse("region/coral_isles"), RegionId.parse("region/ash_trench")),
                "open_sea",
                new SeaEventDef.Window(days, LocalTime.of(18, 0), LocalTime.of(23, 30)),
                chance,
                gapHours,
                duration,
                5,
                null,
                maxPerSeason,
                List.of(),
                List.of(),
                1,
                new SeaEventDef.Storm(1, 5, 0.7, 12, 4),
                null,
                null,
                null,
                SeaEventDef.STORM_LOOP);
    }

    @Test
    void plannerIsDeterministicAndInsideWindow() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        SeaEventDef def = event(1.0, 20, 30, EnumSet.allOf(DayOfWeek.class), 0);
        LocalDate day = LocalDate.of(2026, 11, 18);
        EventPlanner.Plan plan = EventPlanner.plan(def, day, zone).orElseThrow();
        assertEquals(plan, EventPlanner.plan(def, day, zone).orElseThrow());
        LocalTime start = plan.startAt().atZone(zone).toLocalTime();
        assertFalse(start.isBefore(LocalTime.of(18, 0)));
        assertFalse(plan.endAt().atZone(zone).toLocalTime().isAfter(LocalTime.of(23, 30)));
        assertEquals(Duration.ofMinutes(5), Duration.between(plan.warnAt(), plan.startAt()));
        assertTrue(plan.runId().startsWith("storm_20261118"));
        assertTrue(def.regions().contains(plan.region()));
        assertTrue(
                EventPlanner.openPlans(def, plan.startAt().plusSeconds(60), zone).contains(plan));
        assertTrue(
                EventPlanner.openPlans(def, plan.endAt().plusSeconds(1), zone).stream()
                        .noneMatch(plan::equals));
    }

    @Test
    void plannerChanceDaysAndGap() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate wednesday = LocalDate.of(2026, 11, 18);
        assertTrue(
                EventPlanner.plan(
                                event(0.0, 0, 30, EnumSet.allOf(DayOfWeek.class), 0),
                                wednesday,
                                zone)
                        .isEmpty());
        assertTrue(
                EventPlanner.plan(
                                event(1.0, 0, 30, EnumSet.of(DayOfWeek.SATURDAY), 0),
                                wednesday,
                                zone)
                        .isEmpty());
        // With chance 0.35 it fires on roughly 35% of the days over a year
        SeaEventDef sometimes = event(0.35, 0, 30, EnumSet.allOf(DayOfWeek.class), 0);
        int fired = 0;
        for (int i = 0; i < 365; i++) {
            if (EventPlanner.plan(sometimes, wednesday.plusDays(i), zone).isPresent()) {
                fired++;
            }
        }
        assertTrue(fired > 90 && fired < 170, "발동 " + fired + "회");
        SeaEventDef gap = event(1.0, 20, 30, EnumSet.allOf(DayOfWeek.class), 2);
        EventPlanner.Plan plan = EventPlanner.plan(gap, wednesday, zone).orElseThrow();
        Instant recent = plan.startAt().minus(Duration.ofHours(10));
        assertFalse(EventPlanner.allowed(plan, recent, 0, true));
        assertTrue(EventPlanner.allowed(plan, plan.startAt().minus(Duration.ofHours(21)), 0, true));
        assertFalse(EventPlanner.allowed(plan, null, 2, true));
        assertFalse(EventPlanner.allowed(plan, null, 0, false));
    }

    // ------------------------------------------------------------------ plots

    @Test
    void plotSpiralIsBijective() {
        Set<PlotGrid.Cell> seen = new HashSet<>();
        for (long n = 0; n < 2000; n++) {
            PlotGrid.Cell cell = PlotGrid.cell(n);
            assertTrue(seen.add(cell), "중복 칸 " + cell);
            assertEquals(n, PlotGrid.index(cell.x(), cell.z()));
        }
        // The first 9 slots fill a 3×3
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                assertTrue(PlotGrid.index(x, z) < 9);
            }
        }
    }

    @Test
    void plotBoundsAndGrowth() {
        PlotGrid.Bounds small = PlotGrid.bounds(new PlotGrid.Cell(1, -2), 40, 24);
        assertEquals(48, small.minX());
        assertEquals(71, small.maxX());
        assertEquals(-72, small.minZ());
        PlotGrid.Bounds large = PlotGrid.bounds(new PlotGrid.Cell(1, -2), 40, 32);
        assertEquals(small.centerX(), large.centerX(), 1e-9);
        assertEquals(PlotGrid.cellAt(large.minX(), large.minZ(), 40), new PlotGrid.Cell(1, -2));
        assertEquals(24, PlotGrid.size(0, 3, 4, 24, 32));
        assertEquals(32, PlotGrid.size(24, 4, 4, 24, 32));
        // Does not shrink even when reputation is reset (D11)
        assertEquals(32, PlotGrid.size(32, 1, 4, 24, 32));
    }

    // ------------------------------------------------------------------ chart

    @Test
    void chartBitsCountNavigableCells() {
        Polygon square = new Polygon(pts(0, 0, 160, 0, 160, 160, 0, 160));
        ChartMask mask = ChartMask.of(square);
        assertEquals(100, mask.total());
        ChartBits empty = ChartBits.empty(mask);
        ChartBits one = empty.mark(mask, mask.cell(3, 4));
        assertEquals(1, one.visited());
        assertSame(one, one.mark(mask, mask.cell(3, 4)));
        assertEquals(0.01, one.ratio(mask), 1e-9);
        ChartBits restored = ChartBits.of(mask, one.toBytes(), 0b101);
        assertEquals(1, restored.visited());
        assertTrue(restored.isClaimed(1));
        assertFalse(restored.isClaimed(2));
        assertEquals(2, restored.claimedSteps());
        assertEquals(50, ChartBits.filled(mask, 0.5, 0).visited());
        assertEquals(-1, mask.cell(50, 50));
    }

    // ------------------------------------------------------------------ ships

    @Test
    void shipMath() {
        ShipDef explorer =
                new ShipDef(
                        HarborId.parse("ship/ocean_explorer"),
                        "원양 탐사선",
                        ItemId.parse("item/boat/ocean_explorer"),
                        true,
                        "mangrove",
                        60000,
                        0.60,
                        18,
                        200,
                        Set.of(),
                        ShipDef.Requires.NONE,
                        false,
                        null,
                        null,
                        Map.of(
                                UpgradePath.SPEED,
                                List.of(0.05, 0.10, 0.15),
                                UpgradePath.CARGO,
                                List.of(9.0, 18.0, 27.0),
                                UpgradePath.HULL,
                                List.of(0.15, 0.30, 0.50)),
                        ShipDef.ENGINE_LOOP,
                        1.0);
        Map<UpgradePath, Integer> none = Map.of();
        Map<UpgradePath, Integer> maxed =
                Map.of(UpgradePath.SPEED, 3, UpgradePath.CARGO, 3, UpgradePath.HULL, 2);
        assertEquals(1.6, ShipMath.baseSpeed(explorer, none), 1e-9);
        assertEquals(1.75, ShipMath.baseSpeed(explorer, maxed), 1e-9);
        assertEquals(200, ShipMath.maxDurability(explorer, none));
        assertEquals(260, ShipMath.maxDurability(explorer, maxed));
        assertEquals(45, ShipMath.cargo(explorer, maxed));
        // Upgrade cost = price × 10 · 20 · 30% ('13' 5.1: ocean explorer 6,000 / 12,000 / 18,000)
        assertEquals(6000, ShipMath.upgradeCost(explorer, 0.10));
        assertEquals(18000, ShipMath.upgradeCost(explorer, 0.30));
        // Exploration automatic passive +10% · storm ×0.7
        assertEquals(1.6 * 1.1 * 0.7, ShipMath.speed(1.6, 1.1, 0.7), 1e-9);
        assertEquals(0.04 * 0.6, ShipMath.impulse(0.04, 1.6), 1e-9);
        assertEquals(0.82, ShipMath.stormDamage(1, 0.82), 1e-9);
        // Repair cost 3% (10% when wrecked) × repairer −30%
        assertEquals(1800, ShipMath.repairCost(60000, 0.03, 1.0));
        assertEquals(4200, ShipMath.repairCost(60000, 0.10, 0.7));
        assertEquals(0, ShipMath.repairCost(0, 0.10, 1.0));
    }

    // ------------------------------------------------------------------ treasure

    @Test
    void treasureRollStaysInRange() {
        RegionId harbor = RegionId.parse("region/harbor");
        TreasureTable table =
                new TreasureTable(
                        Map.of(harbor, 0.5),
                        List.of(
                                new TreasureTable.Tier(
                                        "common",
                                        null,
                                        70,
                                        400,
                                        800,
                                        1,
                                        0.0,
                                        List.of(
                                                new TreasureTable.Loot(
                                                        ItemId.parse("item/bait/earthworm"),
                                                        1,
                                                        8,
                                                        16))),
                                new TreasureTable.Tier(
                                        "ghost", null, 0, 1000, 1000, 0, 1.0, List.of())));
        Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            TreasureTable.Roll roll =
                    table.roll(table.tier("common", harbor).orElseThrow(), harbor, random);
            assertTrue(roll.gold() >= 200 && roll.gold() <= 400);
            int count = roll.items().get(ItemId.parse("item/bait/earthworm"));
            assertTrue(count >= 8 && count <= 16);
            assertFalse(roll.hint());
        }
        // Weight 0 (event only) is never drawn when making a map
        for (int i = 0; i < 100; i++) {
            assertEquals("common", table.pickTier(harbor, random).orElseThrow().id());
        }
        assertTrue(table.roll(table.tier("ghost", harbor).orElseThrow(), harbor, random).hint());
        assertEquals(Optional.empty(), table.tier("epic", harbor));
        assertNotEquals(0, table.mult(harbor));
    }

    @Test
    void tankBoundsRoundTripAndContain() {
        var b = new wiki.creeper.harbor.world.api.TankBounds("harbor_homes", 1, 64, -3, 3, 65, -2);
        assertEquals(
                b, wiki.creeper.harbor.world.api.TankBounds.decode("harbor_homes", b.encode()));
        assertEquals(3, b.sizeX());
        assertEquals(2, b.sizeY());
        assertEquals(2, b.sizeZ());
        assertTrue(b.contains(2, 65, -2));
        assertFalse(b.contains(4, 64, -3));
        assertFalse(b.single());
        assertEquals(null, wiki.creeper.harbor.world.api.TankBounds.decode("w", "1,2,3"));
    }

    @Test
    void spreadPicksEvenlyPlacedCells() {
        List<Integer> cells = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertEquals(List.of(1, 4, 7, 10), PlotGrid.spread(cells, 4));
        assertEquals(List.of(0, 1), PlotGrid.spread(List.of(0, 1), 2));
        assertEquals(List.of(6), PlotGrid.spread(cells, 1));
    }
}
