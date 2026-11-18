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

/** Quest 콘텐츠 형식과 검사. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class QuestContent {

    public static final List<ContentType<?>> TYPES =
            List.of(QuestDef.TYPE, BoardRules.TYPE, ChronicleDef.TYPE, ProgressRules.TYPE);

    private QuestContent() {}

    /** 형식과 서버 API가 필요 없는 검사를 등록한다 (harbor-tools). 목표 유형은 기본 유형만 안다. */
    public static void register(ContentLoader loader) {
        TYPES.forEach(loader::register);
        loader.addCheck(QuestDef.TYPE, QUEST_CHECK);
        loader.addCheck(QuestDef.TYPE, objectiveTypes(Objective.BUILTIN));
        loader.addCheck(ChronicleDef.TYPE, CHRONICLE_CHECK);
    }

    /**
     * 퀘스트 하나 검사 (docs 20 6.6 콘텐츠 검사): 순환 선행 퀘스트, 품질 상한 위반, 연대기 조각 ID, deliver·talk 대상 NPC,
     * job_advance는 job 유형만.
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

    /** 목표 유형이 등록되어 있는지 (서버는 ObjectiveTypeRegistry 목록, harbor-tools는 기본 유형). */
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

    /** 장의 final이 메인 퀘스트인지, 조각의 장이 장 목록에 있는지. */
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

    /** 직업 단계별 품질 상한 (balance.yml jobs.quality_cap, 기본 1차 ★3 · 2차 ★4 · 3차 ★5). */
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

    /** DFS (0 미방문, 1 방문 중, 2 끝). 방문 중인 노드를 다시 만나면 순환. */
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
