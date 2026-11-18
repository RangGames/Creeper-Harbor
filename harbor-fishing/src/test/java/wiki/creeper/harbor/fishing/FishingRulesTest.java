package wiki.creeper.harbor.fishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;
import wiki.creeper.harbor.fishing.content.Effect;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.content.FoodBuffs;
import wiki.creeper.harbor.fishing.content.JunkTable;
import wiki.creeper.harbor.fishing.food.ActiveFood;
import wiki.creeper.harbor.fishing.food.FoodRule;
import wiki.creeper.harbor.fishing.roll.BaitRule;
import wiki.creeper.harbor.fishing.roll.GradeBonus;
import wiki.creeper.harbor.fishing.roll.JunkRoller;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.sea.SeaEventBook;
import wiki.creeper.harbor.fishing.trap.TrapMath;
import wiki.creeper.harbor.fishing.trap.TrapRoller;

/** 통발 상한(D1)·해상 이벤트 fish_effects·요리 버프 교체·전설의 미끼(D8)·보정 합성·잡템. */
class FishingRulesTest {

    private static final FishingBalance BALANCE = new FishingBalance(Balance.of(Map.of()));
    private static final RegionId HARBOR = RegionId.parse("region/harbor");
    private static final RegionId CORAL = RegionId.parse("region/coral_isles");
    private static final Instant T0 = Instant.parse("2026-11-18T00:00:00Z");

    private static FishDef fish(String name, Grade grade, int price) {
        return new FishDef(
                FishId.parse("fish/" + name),
                1,
                name,
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
                20,
                40,
                price,
                100,
                false,
                "harbor:fish/" + name,
                1);
    }

    /** 늘 같은 가치를 내는 통발 판정. */
    private static TrapMath.Roller fixedValue(double value) {
        FishDef f = fish("anchovy", Grade.COMMON, 5);
        return () -> new TrapMath.Rolled(f, 10, Quality.of(1), value);
    }

    // ------------------------------------------------------------------ 통발 (D1)

    @Test
    void trapCycleUsesMultiplierRotationAndFloor() {
        // 산호 0.9 → 7.2시간, 통발 회전 2랭크(−2) → 5.2시간
        assertEquals(Duration.ofMinutes(432), TrapMath.cycle(8, 0.9, 0, 2));
        assertEquals(Duration.ofMinutes(312), TrapMath.cycle(8, 0.9, -2, 2));
        // 하한
        assertEquals(Duration.ofHours(2), TrapMath.cycle(8, 1, -10, 2));
    }

    @Test
    void trapAccruesAtMostTwoCyclesAndDropsOverflow() {
        Duration cycle = Duration.ofHours(8);
        TrapMath.Accrual one = TrapMath.accrue(T0, T0.plus(Duration.ofHours(9)), cycle, 2);
        assertEquals(1, one.cycles());
        assertEquals(T0.plus(cycle), one.lastCalc(), "남은 1시간은 다음 주기로 이어진다");

        TrapMath.Accrual many = TrapMath.accrue(T0, T0.plus(Duration.ofDays(3)), cycle, 2);
        assertEquals(2, many.cycles());
        assertEquals(T0.plus(Duration.ofDays(3)), many.lastCalc(), "2주기를 넘긴 시간은 버린다");

        assertEquals(0, TrapMath.accrue(T0, T0.plus(Duration.ofHours(7)), cycle, 2).cycles());
    }

    @Test
    void revenueCapIsFortyFiftySixtyPercentOfHourlyGold() {
        assertEquals(0.4, BALANCE.trapRevenueRatio(false, 3), 1e-9);
        assertEquals(0.4, BALANCE.trapRevenueRatio(true, 1), 1e-9);
        assertEquals(0.5, BALANCE.trapRevenueRatio(true, 2), 1e-9);
        assertEquals(0.6, BALANCE.trapRevenueRatio(true, 3), 1e-9);
        // 산호 군도 1,800G/h × 0.4 = 720G (계정 전체 하루 상한)
        assertEquals(720, TrapMath.accountCap(1800, 0.4), 1e-9);
    }

    @Test
    void productionStopsAtAccountCapWithoutUsingBait() {
        // 슬롯 9 × 2주기, 미끼 충분, 한 마리 100G, 계정 상한 720G에 이미 다른 통발이 300G 사용
        TrapMath.Production p =
                TrapMath.produce(
                        2, 9, 50, 1, 0, 300, 720, fixedValue(100), T0, new SplittableRandom(1));
        assertEquals(4, p.catches().size(), "300 + 4 × 100 = 700, 다섯 번째는 720을 넘는다");
        assertEquals(4, p.baitUsed(), "산출하지 않은 슬롯은 미끼를 쓰지 않는다");
        assertEquals(400, p.value(), 1e-9);
        assertTrue(p.capReached());
    }

    @Test
    void productionIsLimitedByBaitAndSlots() {
        TrapMath.Production p =
                TrapMath.produce(
                        2, 3, 4, 1, 0, 0, 1_000_000, fixedValue(5), T0, new SplittableRandom(1));
        assertEquals(4, p.catches().size(), "주기 1: 3마리, 주기 2: 남은 미끼 1개");
        assertEquals(4, p.baitUsed());
        assertFalse(p.capReached());

        TrapMath.Production none =
                TrapMath.produce(
                        2, 3, 0, 1, 1.0, 0, 1_000_000, fixedValue(5), T0, new SplittableRandom(1));
        assertEquals(0, none.catches().size(), "미끼가 없으면 풍어 통발 추가분도 없다");
    }

    @Test
    void bountifulTrapAddsOneFishPerCycle() {
        TrapMath.Production p =
                TrapMath.produce(
                        2, 3, 10, 1, 1.0, 0, 1_000_000, fixedValue(5), T0, new SplittableRandom(1));
        assertEquals(8, p.catches().size(), "주기마다 3 + 1");
        assertEquals(6, p.baitUsed(), "추가분은 미끼를 쓰지 않는다");
    }

    @Test
    void slotBonusIsPerTrapAndTrapCountIsCapped() {
        // 결정 D1: 통발 슬롯 +3은 통발 1개당
        assertEquals(12, TrapMath.effectiveSlots(9, 0, 3));
        assertEquals(13, TrapMath.effectiveSlots(9, 1, 3.4));
        assertEquals(2, TrapMath.maxTraps(2, 0, 4));
        assertEquals(4, TrapMath.maxTraps(2, 2, 4));
        assertEquals(4, TrapMath.maxTraps(2, 5, 4));
    }

    @Test
    void trapWindowResetsAfterTwentyFourHours() {
        Duration window = Duration.ofHours(24);
        assertEquals(300, TrapMath.windowValue(300, T0, T0.plus(Duration.ofHours(23)), window));
        assertEquals(0, TrapMath.windowValue(300, T0, T0.plus(Duration.ofHours(24)), window));
    }

    @Test
    void trapSpecReadsItemStats() {
        TrapMath.Spec coral =
                TrapMath.Spec.of(
                        Map.of("slots", 9.0, "max_grade", 3.0, "cycle_mult", 0.9, "tier", 3.0));
        assertEquals(9, coral.slots());
        assertEquals(Grade.RARE, coral.maxGrade());
        assertEquals(3, coral.tier());
        assertFalse(coral.deep());
        assertEquals(Grade.COMMON, TrapMath.Spec.of(Map.of()).maxGrade());
    }

    @Test
    void trapRollerRespectsMaxGradeAndSkipsLegendaryAndBaitFish() {
        FishDef common = fish("c", Grade.COMMON, 5);
        FishDef rare = fish("r", Grade.RARE, 45);
        FishDef legend = fish("l", Grade.LEGENDARY, 3000);
        Map<Grade, List<FishDef>> pool =
                Map.of(
                        Grade.COMMON, List.of(common),
                        Grade.RARE, List.of(rare),
                        Grade.LEGENDARY, List.of(legend));
        TrapRoller woodRoller =
                new TrapRoller(
                        pool,
                        Grade.COMMON,
                        f -> true,
                        BALANCE,
                        Quality.of(3),
                        new SplittableRandom(3));
        for (int i = 0; i < 500; i++) {
            assertEquals(Grade.COMMON, woodRoller.next().fish().grade());
        }
        TrapRoller coralRoller =
                new TrapRoller(
                        pool,
                        Grade.RARE,
                        f -> true,
                        BALANCE,
                        Quality.of(3),
                        new SplittableRandom(3));
        boolean sawRare = false;
        for (int i = 0; i < 2000; i++) {
            TrapMath.Rolled r = coralRoller.next();
            assertTrue(r.fish().grade() != Grade.LEGENDARY);
            assertTrue(r.quality().stars() <= 3, "주인의 직업 상한");
            sawRare |= r.fish().grade() == Grade.RARE;
        }
        assertTrue(sawRare);
    }

    // ------------------------------------------------------------------ 해상 이벤트 fish_effects

    @Test
    void speciesWeightAppliesOnlyInsideEventRegionUntilEnd() {
        SeaEventBook book = new SeaEventBook();
        book.apply(
                new SeaEventMessage(
                        "great_migration_1",
                        SeaEventMessage.State.START,
                        "region/harbor",
                        T0.plus(Duration.ofHours(3)),
                        List.of(
                                FishEffect.speciesWeight("fish/mackerel", 3),
                                FishEffect.speciesWeight("harbor:fish/mackerel", 2))),
                T0);
        assertEquals(6.0, book.speciesMult(HARBOR, T0).get("harbor:fish/mackerel"), 1e-9);
        assertTrue(book.speciesMult(CORAL, T0).isEmpty());
        assertTrue(
                book.speciesMult(HARBOR, T0.plus(Duration.ofHours(3))).isEmpty(),
                "끝 시각이 지나면 스스로 끝난다");

        book.apply(
                new SeaEventMessage(
                        "great_migration_1",
                        SeaEventMessage.State.START,
                        null,
                        T0.plus(Duration.ofHours(3)),
                        List.of(FishEffect.speciesWeight("fish/mackerel", 3))),
                T0);
        assertEquals(
                3.0,
                book.speciesMult(CORAL, T0).get("harbor:fish/mackerel"),
                1e-9,
                "지역 없음 = 서버 전체");
        book.apply(
                new SeaEventMessage(
                        "great_migration_1", SeaEventMessage.State.END, null, null, List.of()),
                T0);
        assertTrue(book.ids(T0).isEmpty());
    }

    @Test
    void mythicWindowOpensOnlyForMythicFishAndRollUsesIt() {
        FishDef mythic = fish("leader", Grade.MYTHIC, 0);
        FishDef common = fish("c", Grade.COMMON, 5);
        SeaEventBook book = new SeaEventBook();
        book.apply(
                new SeaEventMessage(
                        "ghost_ship_1",
                        SeaEventMessage.State.START,
                        "region/harbor",
                        T0.plus(Duration.ofHours(1)),
                        List.of(
                                FishEffect.mythic("fish/leader", 1.0, 3),
                                FishEffect.mythic("fish/c", 1.0, 3))),
                T0);
        List<RollContext.MythicWindow> windows =
                book.mythicWindows(
                        HARBOR,
                        T0,
                        id ->
                                id.equals("harbor:fish/leader")
                                        ? Optional.of(mythic)
                                        : id.equals("harbor:fish/c")
                                                ? Optional.of(common)
                                                        .filter(f -> f.grade() == Grade.MYTHIC)
                                                : Optional.empty());
        assertEquals(1, windows.size());
        assertEquals("ghost_ship_1", windows.getFirst().eventId());
        assertEquals(3, windows.getFirst().cap());
        assertTrue(book.mythicWindows(CORAL, T0, id -> Optional.of(mythic)).isEmpty(), "참가자만");
    }

    // ------------------------------------------------------------------ 요리 버프 (1종만)

    @Test
    void newFoodReplacesPreviousBuffSource() {
        FoodBuffs.Buff stew =
                new FoodBuffs.Buff(
                        ItemId.parse("item/food/spicy_stew"),
                        Duration.ofMinutes(10),
                        List.of(new Effect(StatKey.of("bite_wait"), Modifier.Op.ADD, -0.05)),
                        true);
        FoodBuffs.Buff tea =
                new FoodBuffs.Buff(
                        ItemId.parse("item/food/lucky_tea"),
                        Duration.ofMinutes(30),
                        List.of(new Effect(StatKey.of("size_luck"), Modifier.Op.ADD, 0.1)),
                        true);
        FoodRule.Replacement first = FoodRule.replace(null, stew, T0, 0);
        assertNull(first.removeSource());
        assertEquals("food:spicy_stew", first.next().source());
        assertEquals(T0.plus(Duration.ofMinutes(10)), first.next().expiresAt());

        // 요리사 2랭크 (+50%) 지속 시간
        FoodRule.Replacement second =
                FoodRule.replace(first.next(), tea, T0.plus(Duration.ofMinutes(1)), 0.5);
        assertEquals("food:spicy_stew", second.removeSource(), "앞 요리 버프를 지운다");
        assertEquals(T0.plus(Duration.ofMinutes(46)), second.next().expiresAt());

        // 같은 요리를 다시 먹으면 시간을 새로 시작 (더하지 않음)
        ActiveFood current = second.next();
        FoodRule.Replacement again =
                FoodRule.replace(current, tea, T0.plus(Duration.ofMinutes(2)), 0);
        assertEquals("food:lucky_tea", again.removeSource());
        assertEquals(T0.plus(Duration.ofMinutes(32)), again.next().expiresAt());
    }

    // ------------------------------------------------------------------ 전설의 미끼 (D8)

    @Test
    void legendBaitIsConsumedOnlyWhenLegendaryCaught() {
        assertFalse(BaitRule.consumeOnHook(true));
        assertTrue(BaitRule.consumeOnHook(false));
        assertTrue(BaitRule.consumeOnResult(true, MinigameResult.CAUGHT, Grade.LEGENDARY));
        assertFalse(
                BaitRule.consumeOnResult(true, MinigameResult.CAUGHT, Grade.EPIC),
                "일반 어종이 걸리면 남는다");
        assertFalse(
                BaitRule.consumeOnResult(true, MinigameResult.LINE_BROKE, Grade.LEGENDARY),
                "실패하면 남는다");
        assertFalse(BaitRule.consumeOnResult(true, MinigameResult.ESCAPED, Grade.LEGENDARY));
        assertFalse(
                BaitRule.consumeOnResult(false, MinigameResult.CAUGHT, Grade.LEGENDARY),
                "일반 미끼는 챔질 때 이미 씀");
        // 반환: 전설의 미끼는 차감하지 않았으므로 돌려줄 것도 없다
        assertFalse(BaitRule.refund(true, MinigameResult.ABORTED, 0, 1));
        assertTrue(BaitRule.refund(false, MinigameResult.ABORTED, 0.99, 0));
        assertTrue(BaitRule.refund(false, MinigameResult.CAUGHT, 0.05, 0.08), "미끼 절약");
        assertFalse(BaitRule.refund(false, MinigameResult.CAUGHT, 0.5, 0.08));
        assertFalse(BaitRule.refund(false, MinigameResult.LINE_BROKE, 0, 1));
    }

    // ------------------------------------------------------------------ 희귀 이상 보정 합성

    @Test
    void gradeBonusMultipliesCategoriesUsesBestFoodAndCaps() {
        GradeBonus.Sum sum =
                new GradeBonus.Sum()
                        .add("bait:shiny", 0.15)
                        .add("job:collect:2", 0.10)
                        .add("food:sashimi_platter", 0.03)
                        .add("food:other", 0.02)
                        .add("event:storm", 0.0);
        assertEquals(0.03, sum.of(GradeBonus.Category.FOOD), 1e-9, "요리는 가장 큰 1종만");
        assertEquals(1.15 * 1.10 * 1.03 - 1, sum.combined(), 1e-9);
        GradeBonus.Sum big =
                new GradeBonus.Sum().add("skill:collect/hunter", 0.5).add("event:migration", 0.5);
        assertEquals(0.6, big.capped(0.6), 1e-9, "합계 배율 1.6 상한");
        assertEquals(GradeBonus.Category.FOOD, GradeBonus.categoryOf("codex_season"));
        assertEquals(GradeBonus.Category.EVENT, GradeBonus.categoryOf("spot:school"));
    }

    // ------------------------------------------------------------------ 잡템

    @Test
    void junkRollsEachEntryAndTreasureNeedsHook() {
        JunkTable table =
                new JunkTable(
                        Map.of(
                                CORAL,
                                List.of(
                                        new JunkTable.Entry(
                                                ItemId.parse("item/material/coral_fragment"),
                                                1.0,
                                                1),
                                        new JunkTable.Entry(
                                                ItemId.parse("item/material/none"), 0.0, 1))),
                        1.0,
                        Set.of());
        JunkRoller.Drops drops = JunkRoller.roll(table, CORAL, 0, false, new SplittableRandom(1));
        assertEquals(1, drops.items().size());
        assertFalse(drops.treasure(), "TreasureDropHook이 없으면 보물 지도 없음");
        assertTrue(JunkRoller.roll(table, CORAL, 0, true, new SplittableRandom(1)).treasure());
        assertTrue(
                JunkRoller.roll(table, HARBOR, 0, false, new SplittableRandom(1))
                        .items()
                        .isEmpty());
    }
}
