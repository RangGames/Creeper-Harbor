package wiki.creeper.harbor.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.util.SourcePos;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.CatchFilter;
import wiki.creeper.harbor.quest.content.CutsceneAt;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.ObjectiveMode;
import wiki.creeper.harbor.quest.content.ProgressRules;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Repeat;
import wiki.creeper.harbor.quest.content.Requires;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.content.Target;
import wiki.creeper.harbor.quest.logic.BoardRoller;
import wiki.creeper.harbor.quest.logic.CatchFacts;
import wiki.creeper.harbor.quest.logic.ClimaxMath;
import wiki.creeper.harbor.quest.logic.Cycles;
import wiki.creeper.harbor.quest.logic.DeliverPlan;
import wiki.creeper.harbor.quest.logic.Matchers;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;
import wiki.creeper.harbor.quest.logic.RefIds;
import wiki.creeper.harbor.quest.logic.Requirements;

class QuestLogicTest {

    static final FishId MACKEREL = FishId.parse("fish/harbor_mackerel");
    static final FishId BASS = FishId.parse("fish/storm_sea_bass");
    static final RegionId HARBOR = RegionId.parse("region/harbor");

    static Target target(String raw) {
        return Target.parse(
                raw,
                new SourcePos("t", 1, 1),
                new ParseContext(),
                e -> {
                    throw new AssertionError(e);
                });
    }

    static Objective obj(String key, String type, String target, int count) {
        return new Objective(
                key,
                type,
                target(target),
                count,
                null,
                0,
                null,
                null,
                CatchFilter.ROD,
                null,
                null,
                "season",
                null,
                false);
    }

    static Objective catchObj(
            String target, int count, int quality, double size, CatchFilter source) {
        return new Objective(
                "c",
                "catch",
                target(target),
                count,
                quality > 0 ? Quality.of(quality) : null,
                size,
                null,
                null,
                source,
                null,
                null,
                "season",
                null,
                false);
    }

    static QuestDef quest(ObjectiveMode mode, Objective... objectives) {
        List<Objective> list = List.of(objectives);
        return new QuestDef(
                new QuestId(HarborId.parse("quest/region/test")),
                "T",
                List.of(),
                QuestType.REGION,
                -1,
                Requires.NONE,
                NpcId.parse("npc/bawi"),
                null,
                list,
                mode,
                Rewards.NONE,
                Repeat.NONE,
                null,
                CutsceneAt.COMPLETE,
                false,
                true,
                null,
                null,
                null,
                list.size() * 31);
    }

    // ------------------------------------------------------------------ Objective matching

    @Test
    void catchMatchesFishGradeRegionAndFilters() {
        CatchFacts mackerel = CatchFacts.fish(MACKEREL, Grade.COMMON, HARBOR, 2, 30, false);
        CatchFacts bass = CatchFacts.fish(BASS, Grade.UNCOMMON, HARBOR, 4, 55, false);
        assertTrue(
                Matchers.matchesFish(
                        catchObj("fish/harbor_mackerel", 1, 0, 0, CatchFilter.ROD), mackerel));
        assertFalse(
                Matchers.matchesFish(
                        catchObj("fish/harbor_mackerel", 1, 0, 0, CatchFilter.ROD), bass));
        assertTrue(
                Matchers.matchesFish(catchObj("grade:uncommon+", 1, 0, 0, CatchFilter.ROD), bass));
        assertFalse(
                Matchers.matchesFish(
                        catchObj("grade:uncommon+", 1, 0, 0, CatchFilter.ROD), mackerel));
        assertFalse(Matchers.matchesFish(catchObj("grade:common", 1, 0, 0, CatchFilter.ROD), bass));
        assertTrue(
                Matchers.matchesFish(
                        catchObj("region:region/harbor", 1, 0, 0, CatchFilter.ROD), mackerel));
        assertTrue(Matchers.matchesFish(catchObj("any", 1, 3, 0, CatchFilter.ROD), bass));
        assertFalse(Matchers.matchesFish(catchObj("any", 1, 3, 0, CatchFilter.ROD), mackerel));
        assertFalse(Matchers.matchesFish(catchObj("any", 1, 0, 60, CatchFilter.ROD), bass));
    }

    @Test
    void trapCountsOnlyForSourceAny() {
        CatchFacts trap = CatchFacts.fish(MACKEREL, Grade.COMMON, HARBOR, 1, 20, true);
        assertFalse(Matchers.matchesFish(catchObj("any", 1, 0, 0, CatchFilter.ROD), trap));
        assertTrue(Matchers.matchesFish(catchObj("any", 1, 0, 0, CatchFilter.ANY), trap));
    }

    @Test
    void visitMatchesRegionAndPoint() {
        Objective region = obj("v", "visit", "region:region/harbor", 1);
        assertTrue(Matchers.matchesVisit(region, HARBOR.id(), "harbor_main", 0, 0, 0));
        Objective point = obj("p", "visit", "point:harbor_main,10,64,10,5", 1);
        assertTrue(Matchers.matchesVisit(point, null, "harbor_main", 12, 64, 12));
        assertFalse(Matchers.matchesVisit(point, null, "harbor_main", 20, 64, 20));
        assertFalse(Matchers.matchesVisit(point, null, "other", 10, 64, 10));
    }

    @Test
    void idAndRawSignals() {
        Objective boss = obj("b", "boss", "boss/swamp_catfish", 1);
        assertTrue(Matchers.matchesId(boss, HarborId.parse("boss/swamp_catfish")));
        assertFalse(Matchers.matchesId(boss, HarborId.parse("boss/other")));
        assertTrue(Matchers.matchesId(obj("a", "boss", "any", 1), HarborId.parse("boss/other")));
        assertTrue(
                Matchers.matchesRaw(
                        obj("c", "chart_percent", "region:region/harbor", 1),
                        "harbor:region/harbor"));
    }

    // ------------------------------------------------------------------ State machine

    @Test
    void sequenceOnlyAdvancesCurrentObjective() {
        QuestDef q =
                quest(
                        ObjectiveMode.SEQUENCE,
                        obj("a", "catch", "any", 2),
                        obj("b", "talk", "any", 1));
        Progress p = Progress.start(q.hash(), Cycles.ONCE, Progress.Snapshot.NONE, 0);
        assertFalse(QuestMachine.add(q, p, "b", 1).changed());
        QuestMachine.Step s1 = QuestMachine.add(q, p, "a", 5);
        assertEquals(2, s1.progress().count("a"));
        assertEquals(List.of("a"), s1.completedKeys());
        assertEquals(1, s1.progress().index());
        assertFalse(QuestMachine.ready(q, s1.progress()));
        QuestMachine.Step s2 = QuestMachine.add(q, s1.progress(), "b", 1);
        assertTrue(QuestMachine.ready(q, s2.progress()));
        assertEquals(QuestState.READY, QuestMachine.afterProgress(QuestState.ACTIVE, true));
    }

    @Test
    void allModeAndGauges() {
        QuestDef q =
                quest(
                        ObjectiveMode.ALL,
                        obj("lvl", "job_level", "any", 10),
                        obj("c", "catch", "any", 1));
        Progress p = Progress.start(q.hash(), Cycles.ONCE, Progress.Snapshot.NONE, 0);
        Progress p1 = QuestMachine.set(q, p, "lvl", 7).progress();
        assertEquals(7, p1.count("lvl"));
        assertFalse(QuestMachine.set(q, p1, "lvl", 5).changed(), "게이지는 내려가지 않는다");
        Progress p2 = QuestMachine.set(q, p1, "lvl", 99).progress();
        assertEquals(10, p2.count("lvl"));
        assertFalse(QuestMachine.ready(q, p2));
        assertTrue(QuestMachine.ready(q, QuestMachine.add(q, p2, "c", 1).progress()));
    }

    @Test
    void deliverIsCheckedAtTurnInSoQuestIsReady() {
        QuestDef q =
                quest(
                        ObjectiveMode.SEQUENCE,
                        obj("c", "catch", "any", 1),
                        obj("d", "deliver", "any", 3));
        Progress p =
                QuestMachine.add(
                                q,
                                Progress.start(0, Cycles.ONCE, Progress.Snapshot.NONE, 0),
                                "c",
                                1)
                        .progress();
        assertTrue(QuestMachine.ready(q, p));
        assertTrue(QuestMachine.current(q, p).isEmpty());
    }

    @Test
    void migrateCarriesByKeyAndClamps() {
        QuestDef old =
                quest(
                        ObjectiveMode.SEQUENCE,
                        obj("a", "catch", "any", 10),
                        obj("b", "catch", "any", 5));
        Progress p =
                Progress.start(old.hash(), Cycles.ONCE, Progress.Snapshot.NONE, 0)
                        .withCounts(Map.of("a", 10, "b", 4, "gone", 3), 1);
        QuestDef next =
                quest(
                        ObjectiveMode.SEQUENCE,
                        obj("a", "catch", "any", 6),
                        obj("new", "catch", "any", 2),
                        obj("b", "catch", "any", 3));
        Progress moved = QuestMachine.migrate(next, p);
        assertEquals(6, moved.count("a"));
        assertEquals(3, moved.count("b"));
        assertEquals(0, moved.count("new"));
        assertFalse(moved.counts().containsKey("gone"));
        assertEquals(1, moved.index(), "새 목표부터 다시 진행");
        assertEquals(next.hash(), moved.version());
        assertEquals(
                QuestState.ACTIVE,
                QuestMachine.afterProgress(QuestState.READY, QuestMachine.ready(next, moved)));
    }

    @Test
    void acceptAndAbandonRules() {
        assertTrue(QuestMachine.canAccept(QuestState.AVAILABLE, false));
        assertFalse(QuestMachine.canAccept(QuestState.ACTIVE, false));
        assertFalse(QuestMachine.canAccept(QuestState.COMPLETED, true));
        assertTrue(QuestMachine.canAccept(QuestState.COMPLETED, false));
        QuestDef q = quest(ObjectiveMode.ALL, obj("a", "catch", "any", 1));
        assertTrue(QuestMachine.canAbandon(q, QuestState.ACTIVE));
        assertFalse(QuestMachine.canAbandon(q, QuestState.COMPLETED));
        assertTrue(QuestMachine.canTurnIn(QuestState.READY));
    }

    // ------------------------------------------------------------------ Progress JSON · cycles ·
    // board

    @Test
    void progressJsonRoundTrip() {
        Progress p =
                new Progress(
                        42,
                        Map.of("a", 3),
                        1,
                        "d2027-05-03",
                        new Progress.Snapshot(150, 1, 10, 0, "harbor:region/harbor"),
                        List.of("grant"),
                        1234L);
        Progress back = Progress.fromJson(p.toJson());
        assertEquals(p, back);
        assertEquals("once", Progress.fromJson("{broken").cycle());
    }

    @Test
    void cycles() {
        assertEquals("d2027-05-03", Cycles.of(Repeat.DAILY, LocalDate.of(2027, 5, 3), 1, 2));
        assertEquals("w1-2", Cycles.of(Repeat.WEEKLY, LocalDate.of(2027, 5, 3), 1, 2));
        assertEquals("once", Cycles.of(Repeat.NONE, LocalDate.of(2027, 5, 3), 1, 2));
    }

    @Test
    void boardRollIsDeterministicAndDistinct() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<BoardRoller.Candidate> pool =
                List.of(
                        new BoardRoller.Candidate(
                                new QuestId(HarborId.parse("quest/daily/a")), 100),
                        new BoardRoller.Candidate(
                                new QuestId(HarborId.parse("quest/daily/b")), 100),
                        new BoardRoller.Candidate(new QuestId(HarborId.parse("quest/daily/c")), 50),
                        new BoardRoller.Candidate(
                                new QuestId(HarborId.parse("quest/daily/d")), 10));
        List<QuestId> first = BoardRoller.pick(player, "d2027-05-03", pool, 3);
        assertEquals(
                first, BoardRoller.pick(player, "d2027-05-03", List.copyOf(pool.reversed()), 3));
        assertEquals(3, first.stream().distinct().count());
        assertEquals(4, BoardRoller.pick(player, "x", pool, 9).size());
    }

    @Test
    void refIdsFitLedgerLimit() {
        QuestId q = new QuestId(HarborId.parse("quest/main/ch1_dark_lighthouse"));
        assertEquals("q:main/ch1_dark_lighthouse:1:once", RefIds.reward(q, 1, "once"));
        QuestId longId = new QuestId(HarborId.parse("quest/event/" + "x".repeat(70)));
        String ref = RefIds.reward(longId, 1, "d2027-05-03");
        assertTrue(ref.length() <= 64);
        assertEquals(ref, RefIds.reward(longId, 1, "d2027-05-03"));
        assertTrue(RefIds.recap(3, UUID.randomUUID(), 7).length() <= 64);
    }

    // ------------------------------------------------------------------ Requirements · delivery ·
    // climax

    @Test
    void requirementsWeekLock() {
        Requires r =
                new Requires(
                        List.of(),
                        10,
                        1,
                        List.of(new Requires.FlagCond("x", false, true)),
                        4,
                        null,
                        0);
        Requirements.Facts facts = facts(12, 2, false);
        assertEquals(new Requirements.Outcome(false, 4), Requirements.evaluate(r, facts));
        assertTrue(Requirements.evaluate(r, facts(12, 4, false)).met());
        assertEquals(Requirements.Outcome.LOCKED, Requirements.evaluate(r, facts(12, 4, true)));
        assertEquals(Requirements.Outcome.LOCKED, Requirements.evaluate(r, facts(5, 4, false)));
    }

    static Requirements.Facts facts(int level, int week, boolean flag) {
        return new Requirements.Facts() {
            @Override
            public boolean completed(QuestId quest) {
                return false;
            }

            @Override
            public int level() {
                return level;
            }

            @Override
            public int jobTier() {
                return 1;
            }

            @Override
            public boolean playerFlag(String f) {
                return flag;
            }

            @Override
            public boolean serverFlag(String f) {
                return false;
            }

            @Override
            public int week() {
                return week;
            }

            @Override
            public int affinityLevel(NpcId npc) {
                return 0;
            }

            @Override
            public int reputationTier() {
                return 1;
            }
        };
    }

    @Test
    void deliverPicksLowQualityFirst() {
        Objective give =
                new Objective(
                        "d",
                        "deliver",
                        target("fish/harbor_mackerel"),
                        3,
                        null,
                        0,
                        null,
                        null,
                        CatchFilter.ROD,
                        null,
                        null,
                        "season",
                        null,
                        false);
        List<DeliverPlan.Slot> slots =
                List.of(
                        new DeliverPlan.Slot(
                                0,
                                CatchFacts.fish(MACKEREL, Grade.COMMON, HARBOR, 5, 30, false),
                                1,
                                true),
                        new DeliverPlan.Slot(
                                1,
                                CatchFacts.fish(MACKEREL, Grade.COMMON, HARBOR, 1, 30, false),
                                2,
                                false),
                        new DeliverPlan.Slot(
                                2,
                                CatchFacts.fish(MACKEREL, Grade.COMMON, HARBOR, 2, 30, false),
                                4,
                                false),
                        new DeliverPlan.Slot(
                                3,
                                CatchFacts.item(ItemId.parse("item/bait/earthworm")),
                                10,
                                false));
        DeliverPlan.Result r = DeliverPlan.plan(List.of(give), slots);
        assertTrue(r.ok());
        assertEquals(Map.of(1, 2, 2, 1), r.take());
        assertFalse(r.precious());
        DeliverPlan.Result missing = DeliverPlan.plan(List.of(give), List.of(slots.get(1)));
        assertEquals(Map.of("d", 1), missing.missing());
    }

    @Test
    void climaxThreshold() {
        ProgressRules rules = new ProgressRules(0.8, 7, 50, 0.15, 5, true);
        assertEquals(50, ClimaxMath.requiredClears(rules, 100));
        assertEquals(150, ClimaxMath.requiredClears(rules, 1000));
        assertTrue(ClimaxMath.ready(rules, 0.8, 150, 1000));
        assertFalse(ClimaxMath.ready(rules, 0.79, 500, 1000));
        assertFalse(ClimaxMath.ready(rules, 0.9, 149, 1000));
    }
}
