package wiki.creeper.harbor.quest.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * Quest content types and checks. Uses no server API, so harbor-tools (CI) checks with the same
 * code.
 */
public final class QuestContent {

    public static final List<ContentType<?>> TYPES =
            List.of(QuestDef.TYPE, BoardRules.TYPE, ChronicleDef.TYPE, ProgressRules.TYPE);

    private QuestContent() {}

    /**
     * Registers the types and the checks that need no server API (harbor-tools). Only the built-in
     * objective types are known.
     */
    public static void register(ContentLoader loader) {
        TYPES.forEach(loader::register);
        loader.addCheck(QuestDef.TYPE, QUEST_CHECK);
        loader.addCheck(QuestDef.TYPE, objectiveTypes(Objective.BUILTIN));
        loader.addCheck(ChronicleDef.TYPE, CHRONICLE_CHECK);
    }

    /**
     * Checks one quest (docs 20 6.6 content check): circular prerequisites, quality cap violations,
     * chronicle piece IDs, deliver/talk target NPCs, job_advance only on the job type.
     */
    public static final ContentCheck<QuestDef> QUEST_CHECK =
            (quest, check) -> {
                ContentSnapshot snapshot = check.snapshot();
                Map<HarborId, QuestDef> all = snapshot.all(QuestDef.TYPE);
                if (hasCycle(quest.id(), all)) {
                    check.error("선행 퀘스트(requires.quests)가 순환합니다: " + quest.id());
                }
                if (!quest.type().board()) {
                    int cap = qualityCap(snapshot, quest.requires().jobTier());
                    for (Objective o : quest.objectives()) {
                        var q = o.qualityMin();
                        if (q != null && q.stars() > cap) {
                            check.error(
                                    "목표 "
                                            + o.key()
                                            + "의 quality_min ★"
                                            + q.stars()
                                            + "가 직업 "
                                            + quest.requires().jobTier()
                                            + "차 품질 상한 ★"
                                            + cap
                                            + "를 넘습니다");
                        }
                    }
                }
                String piece = quest.rewards().chronicle();
                if (piece != null) {
                    boolean known =
                            snapshot.typeNames().contains(ChronicleDef.TYPE.name())
                                    && snapshot.single(ChronicleDef.TYPE).piece(piece).isPresent();
                    if (!known) {
                        check.error("rewards.chronicle 조각이 chronicle.yml에 없습니다: " + piece);
                    }
                }
                for (Objective o : quest.objectives()) {
                    boolean needsNpc = o.type().equals("deliver") || o.type().equals("talk");
                    boolean hasNpc =
                            o.npc() != null
                                    || o.target().isId("npc")
                                    || quest.turnInNpc().isPresent();
                    if (needsNpc && !hasNpc) {
                        check.error(
                                o.type() + " 목표 " + o.key() + "에 대상 NPC(npc·target·end_npc)가 없습니다");
                    }
                }
                if (quest.rewards().jobAdvance() != 0 && quest.type() != QuestType.JOB) {
                    check.error("rewards.job_advance는 type: job 퀘스트에만 쓸 수 있습니다");
                }
                if (quest.type() == QuestType.JOB
                        && quest.rewards().jobAdvance() != 0
                        && quest.turnInNpc().isEmpty()) {
                    check.error("전직 퀘스트는 보고 NPC(end_npc)가 필요합니다");
                }
                if (quest.donateDelivered() && quest.type() != QuestType.COLLECT) {
                    check.error("donate_delivered는 수집 의뢰(type: collect)에만 쓸 수 있습니다");
                }
            };

    /**
     * Whether the objective type is registered (the ObjectiveTypeRegistry list on the server, the
     * built-in types in harbor-tools).
     */
    public static ContentCheck<QuestDef> objectiveTypes(Set<String> known) {
        Set<String> types = Set.copyOf(known);
        return (quest, check) -> {
            for (Objective o : quest.objectives()) {
                if (!types.contains(o.type())) {
                    check.error("등록되지 않은 목표 유형입니다: " + o.type() + " (목표 " + o.key() + ")");
                }
            }
        };
    }

    /**
     * Whether a chapter's final is a main quest, and whether a piece's chapter is in the chapter
     * list.
     */
    public static final ContentCheck<ChronicleDef> CHRONICLE_CHECK =
            (chronicle, check) -> {
                Map<HarborId, QuestDef> quests = check.snapshot().all(QuestDef.TYPE);
                Set<Integer> chapters = new HashSet<>();
                for (ChronicleDef.Chapter c : chronicle.chapters()) {
                    if (!chapters.add(c.chapter())) {
                        check.error("장 번호가 중복됩니다: " + c.chapter());
                    }
                    QuestDef fin = quests.get(c.finalQuest().id());
                    if (fin != null && (!fin.main() || fin.chapter() != c.chapter())) {
                        check.error(
                                c.chapter()
                                        + "장의 final("
                                        + c.finalQuest()
                                        + ")은 같은 장의 메인 퀘스트여야 합니다");
                    }
                }
                for (ChronicleDef.Piece p : chronicle.pieces()) {
                    if (!chapters.contains(p.chapter())) {
                        check.error("조각 " + p.id() + "의 장 " + p.chapter() + "이 chapters에 없습니다");
                    }
                }
            };

    /**
     * Quality cap per job tier (balance.yml jobs.quality_cap, default tier 1 ★3 · tier 2 ★4 · tier
     * 3 ★5).
     */
    static int qualityCap(ContentSnapshot snapshot, int tier) {
        int fallback = Math.min(5, 2 + tier);
        if (!snapshot.typeNames().contains(Balance.TYPE.name())) {
            return fallback;
        }
        return snapshot.single(Balance.TYPE).integer("jobs.quality_cap." + tier, fallback);
    }

    static boolean hasCycle(QuestId start, Map<HarborId, QuestDef> all) {
        Map<HarborId, Integer> color = new HashMap<>();
        return visit(start.id(), all, color);
    }

    /**
     * DFS (0 unvisited, 1 visiting, 2 done). Meeting a node that is being visited means a cycle.
     */
    private static boolean visit(
            HarborId id, Map<HarborId, QuestDef> all, Map<HarborId, Integer> color) {
        int c = color.getOrDefault(id, 0);
        if (c == 1) {
            return true;
        }
        if (c == 2) {
            return false;
        }
        color.put(id, 1);
        QuestDef q = all.get(id);
        if (q != null) {
            for (QuestId next : q.requires().quests()) {
                if (visit(next.id(), all, color)) {
                    return true;
                }
            }
        }
        color.put(id, 2);
        return false;
    }
}
