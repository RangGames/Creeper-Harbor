package wiki.creeper.harbor.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.SourcePos;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.GiftReaction;
import wiki.creeper.harbor.npc.api.QuestMarker;
import wiki.creeper.harbor.npc.api.ReputationTier;
import wiki.creeper.harbor.npc.content.Conditions;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.content.Spot;
import wiki.creeper.harbor.npc.logic.AffinityMath;
import wiki.creeper.harbor.npc.logic.CameraPlan;
import wiki.creeper.harbor.npc.logic.DialogEval;
import wiki.creeper.harbor.npc.logic.ReputationMath;
import wiki.creeper.harbor.npc.logic.WandererSchedule;

class NpcLogicTest {

    private static final SourcePos POS = new SourcePos("t.yml", 1, 1);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // ------------------------------------------------------------------ dialog conditions

    private static DialogRef ref(String key, Map<String, Object> args) {
        return new DialogRef(key, DialogArgs.of(args), POS);
    }

    @Test
    void entryPicksFirstMatchingRule() {
        DialogDef.Node greet =
                new DialogDef.Node("greet", null, List.of("a"), List.of(), false, POS);
        DialogDef.Node offer =
                new DialogDef.Node("offer", null, List.of("b"), List.of(), false, POS);
        DialogDef def =
                new DialogDef(
                        HarborId.parse("dialog/x"),
                        List.of(
                                new DialogDef.Entry(
                                        List.of(ref("quest_available", Map.of())), "offer", POS),
                                new DialogDef.Entry(List.of(), "greet", POS)),
                        Map.of("greet", greet, "offer", offer));
        assertEquals(Optional.of("greet"), DialogEval.entry(def, r -> false));
        assertEquals(
                Optional.of("offer"),
                DialogEval.entry(def, r -> r.key().equals("quest_available")));
    }

    @Test
    void allConditionsMustHoldAndOptionsAreFiltered() {
        Set<String> flags = Set.of("met");
        DialogRef met = ref("flag", Map.of("value", "met"));
        DialogRef other = ref("flag", Map.of("value", "other"));
        java.util.function.Predicate<DialogRef> test =
                r -> flags.contains(r.args().value().orElse(""));
        assertTrue(DialogEval.all(List.of(met), test));
        assertFalse(DialogEval.all(List.of(met, other), test));
        assertTrue(DialogEval.all(List.of(), test));

        DialogDef.Option a = new DialogDef.Option("l1", null, List.of(met), List.of(), null, POS);
        DialogDef.Option b = new DialogDef.Option("l2", null, List.of(other), List.of(), null, POS);
        DialogDef.Option c = new DialogDef.Option("l3", null, List.of(), List.of(), null, POS);
        DialogDef.Node node =
                new DialogDef.Node("n", null, List.of("p1", "p2"), List.of(a, b, c), false, POS);
        List<DialogEval.Visible> visible = DialogEval.visibleOptions(node, test);
        assertEquals(List.of(0, 2), visible.stream().map(DialogEval.Visible::index).toList());
        assertFalse(DialogEval.optionsOnPage(node, 0)); // First page is 'next'
        assertTrue(DialogEval.optionsOnPage(node, 1));
        assertEquals(1, DialogEval.clampPage(node, 9));
    }

    @Test
    void unreachableFollowsGotoAndNodeArgs() {
        DialogRef accept = ref("quest:accept", Map.of("fail_node", "not_ready"));
        DialogDef.Option o =
                new DialogDef.Option("l", null, List.of(), List.of(accept), "accepted", POS);
        Map<String, DialogDef.Node> nodes = new HashMap<>();
        nodes.put("start", new DialogDef.Node("start", null, List.of(), List.of(o), false, POS));
        nodes.put(
                "accepted", new DialogDef.Node("accepted", null, List.of(), List.of(), false, POS));
        nodes.put(
                "not_ready",
                new DialogDef.Node("not_ready", null, List.of(), List.of(), false, POS));
        nodes.put("orphan", new DialogDef.Node("orphan", null, List.of(), List.of(), false, POS));
        nodes.put("hook", new DialogDef.Node("hook", null, List.of(), List.of(), true, POS));
        DialogDef def =
                new DialogDef(
                        HarborId.parse("dialog/x"),
                        List.of(new DialogDef.Entry(List.of(), "start", POS)),
                        nodes);
        assertEquals(Set.of("orphan"), def.unreachable());
    }

    @Test
    void argsReadScalarsAndLists() {
        DialogArgs args =
                DialogArgs.of(Map.of("value", "12", "time", List.of("night", "dawn"), "x", "a"));
        assertEquals(12, args.integer("value", 0));
        assertTrue(args.isInteger("value"));
        assertFalse(args.isInteger("x"));
        assertEquals(List.of("night", "dawn"), args.strings("time"));
        assertEquals(List.of("a"), args.strings("x"));
    }

    @Test
    void timeAndWeatherKeys() {
        assertEquals("dawn", Conditions.timeKey(23_500));
        assertEquals("day", Conditions.timeKey(6_000));
        assertEquals("dusk", Conditions.timeKey(12_500));
        assertEquals("night", Conditions.timeKey(18_000));
        assertEquals("thunder", Conditions.weatherKey(true, true));
        assertTrue(Conditions.matches(List.of("storm"), "thunder"));
        assertFalse(Conditions.matches(List.of("rain"), "clear"));
        assertTrue(Conditions.matches(List.of(), "clear"));
    }

    // ------------------------------------------------------------------ reputation and affinity

    private static final List<ReputationTier> TIERS =
            List.of(
                    new ReputationTier(1, 0, "이방인"),
                    new ReputationTier(2, 300, "이웃"),
                    new ReputationTier(3, 1_000, "단골"),
                    new ReputationTier(4, 2_000, "항구의 일원"),
                    new ReputationTier(5, 3_500, "항구의 기둥"));

    @Test
    void reputationTierBoundaries() {
        assertEquals(1, ReputationMath.tierFor(0, TIERS));
        assertEquals(1, ReputationMath.tierFor(299, TIERS));
        assertEquals(2, ReputationMath.tierFor(300, TIERS));
        assertEquals(4, ReputationMath.tierFor(3_499, TIERS));
        assertEquals(
                5,
                ReputationMath.tierFor(
                        3_550, TIERS)); // Expected season reputation for a typical player ≈ 3,550
        assertEquals(3, ReputationMath.next(500, TIERS).orElseThrow().tier());
        assertTrue(ReputationMath.next(9_999, TIERS).isEmpty());
    }

    @Test
    void reputationCapAndScale() {
        assertEquals(3, ReputationMath.capped(3, 0, 30));
        assertEquals(2, ReputationMath.capped(3, 28, 30));
        assertEquals(0, ReputationMath.capped(3, 30, 30));
        assertEquals(500, ReputationMath.capped(500, 0, 0)); // No cap
        assertEquals(-50, ReputationMath.capped(-50, 30, 30)); // Deductions ignore the cap
        assertEquals(25, ReputationMath.scaled(20, 1.25)); // Harbor investment +25%
        assertEquals(0, ReputationMath.apply(10, -50));
    }

    private static final List<AffinityLevel> LEVELS =
            List.of(
                    new AffinityLevel(0, 0, "낯섦", 0, List.of()),
                    new AffinityLevel(1, 100, "아는 사이", 0, List.of()),
                    new AffinityLevel(2, 300, "친구", 3, List.of("hidden")),
                    new AffinityLevel(3, 600, "단짝", 5, List.of()),
                    new AffinityLevel(4, 1_000, "신뢰", 8, List.of()));

    @Test
    void affinityLevelsAndGifts() {
        assertEquals(0, AffinityMath.levelFor(99, LEVELS).level());
        assertEquals(2, AffinityMath.levelFor(300, LEVELS).level());
        assertEquals(4, AffinityMath.levelFor(5_000, LEVELS).level());
        assertEquals(15, AffinityMath.giftPoints(15, 1, 0.25));
        assertEquals(23, AffinityMath.giftPoints(15, 3, 0.25)); // 15 × 1.5 = 22.5 → 23
        assertEquals(30, AffinityMath.giftPoints(15, 5, 0.25));
        assertEquals(-5, AffinityMath.giftPoints(-5, 5, 0.25)); // Disliked gifts ignore quality
        assertEquals(GiftReaction.LOVE, AffinityMath.reaction(15, 15, 5));
        assertEquals(GiftReaction.LIKE, AffinityMath.reaction(5, 15, 5));
        assertEquals(GiftReaction.NORMAL, AffinityMath.reaction(1, 15, 5));
        assertEquals(GiftReaction.DISLIKE, AffinityMath.reaction(-5, 15, 5));
        assertEquals(0, AffinityMath.apply(3, -10));
    }

    @Test
    void markerPriority() {
        assertEquals(
                QuestMarker.TURN_IN,
                QuestMarker.max(QuestMarker.AVAILABLE_MAIN, QuestMarker.TURN_IN));
        assertEquals(
                QuestMarker.AVAILABLE_MAIN,
                QuestMarker.max(QuestMarker.AVAILABLE_MAIN, QuestMarker.AVAILABLE));
        assertEquals(
                QuestMarker.IN_PROGRESS,
                QuestMarker.max(QuestMarker.NONE, QuestMarker.IN_PROGRESS));
    }

    // ------------------------------------------------------------------ wanderer schedule

    private static NpcDef.Candidate candidate(String id, int weight) {
        return new NpcDef.Candidate(
                id, new Spot("harbor_main", 0, 64, 0, 0, 0), weight, List.of(), List.of());
    }

    @Test
    void slotsAlignToSeoulHours() {
        Instant a = ZonedDateTime.of(2026, 11, 18, 3, 0, 0, 0, SEOUL).toInstant();
        Instant b = ZonedDateTime.of(2026, 11, 18, 5, 59, 59, 0, SEOUL).toInstant();
        Instant c = ZonedDateTime.of(2026, 11, 18, 6, 0, 0, 0, SEOUL).toInstant();
        long slot = WandererSchedule.slot(a, SEOUL, 3);
        assertEquals(slot, WandererSchedule.slot(b, SEOUL, 3));
        assertEquals(slot + 1, WandererSchedule.slot(c, SEOUL, 3));
        assertEquals(c, WandererSchedule.slotEnd(slot, SEOUL, 3));
        assertEquals(3, WandererSchedule.slotStart(slot, SEOUL, 3).getHour());
    }

    @Test
    void pickIsDeterministicAndWeighted() {
        List<NpcDef.Candidate> candidates = List.of(candidate("a", 90), candidate("b", 10));
        String first =
                WandererSchedule.pick("harbor:npc/peddler", 1, 100, candidates).orElseThrow().id();
        for (int i = 0; i < 5; i++) {
            assertEquals(
                    first,
                    WandererSchedule.pick("harbor:npc/peddler", 1, 100, candidates)
                            .orElseThrow()
                            .id());
        }
        int a = 0;
        for (long slot = 0; slot < 2_000; slot++) {
            if (WandererSchedule.pick("harbor:npc/peddler", 1, slot, candidates)
                    .orElseThrow()
                    .id()
                    .equals("a")) {
                a++;
            }
        }
        assertTrue(a > 1_650 && a < 1_950, "가중치 90% 근처여야 합니다: " + a);
        assertTrue(WandererSchedule.pick("x", 1, 1, List.of()).isEmpty());
    }

    @Test
    void conditionalCandidateVisibility() {
        NpcDef.Candidate storm =
                new NpcDef.Candidate(
                        "pier",
                        new Spot("w", 0, 0, 0, 0, 0),
                        1,
                        List.of("night"),
                        List.of("storm"));
        assertTrue(storm.conditional());
        assertTrue(WandererSchedule.visible(storm, "night", "thunder"));
        assertFalse(WandererSchedule.visible(storm, "night", "rain"));
        assertFalse(WandererSchedule.visible(storm, "day", "thunder"));
    }

    // ------------------------------------------------------------------ cutscene camera

    @Test
    void cameraPlanSplitsLongSegments() {
        List<CutsceneDef.CameraKey> keys =
                List.of(
                        new CutsceneDef.CameraKey(0, new Spot("w", 0, 70, 0, 0, 0)),
                        new CutsceneDef.CameraKey(100, new Spot("w", 100, 70, 0, 90, 0)));
        List<CameraPlan.Move> moves = CameraPlan.plan(keys, 40);
        assertEquals(4, moves.size()); // Start + 40 + 40 + 20
        assertEquals(0, moves.get(1).tick());
        assertEquals(40, moves.get(1).duration());
        assertEquals(40, moves.get(1).target().x(), 1e-9);
        assertEquals(80, moves.get(3).tick());
        assertEquals(20, moves.get(3).duration());
        assertEquals(100, moves.get(3).target().x(), 1e-9);
        assertEquals(50, CameraPlan.at(keys, 50).x(), 1e-9);
        assertEquals(100, CameraPlan.at(keys, 500).x(), 1e-9);
    }

    @Test
    void anglesTakeShortestWay() {
        Spot a = new Spot("w", 0, 0, 0, 350, 0);
        Spot b = new Spot("w", 0, 0, 0, 10, 0);
        assertEquals(360, CameraPlan.lerp(a, b, 0.5).yaw(), 1e-4);
    }
}
