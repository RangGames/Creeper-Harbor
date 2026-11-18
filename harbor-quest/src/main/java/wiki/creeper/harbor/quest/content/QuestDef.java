package wiki.creeper.harbor.quest.content;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 퀘스트 정의 (quests/*.yml, '11. 데이터 설계' 2.6·2.9, docs 20 6.3). ID 규칙: {@code quest/<유형>/<이름>}. 메인은
 * {@code quest/main/ch<N>_<이름>} (DB에는 네임스페이스를 붙인 전체 ID).
 *
 * @param chapter 메인 스토리 장 (프롤로그 0, 1~7, 최종장 8). 메인이 아니면 -1
 * @param hash 목표 구성 해시 (progress JSON의 v, 리로드 때 바뀐 퀘스트 수 보고)
 */
public record QuestDef(
        QuestId id,
        String name,
        List<String> description,
        QuestType type,
        int chapter,
        Requires requires,
        @Nullable NpcId startNpc,
        @Nullable NpcId endNpc,
        List<Objective> objectives,
        ObjectiveMode mode,
        Rewards rewards,
        Repeat repeat,
        @Nullable HarborId cutscene,
        CutsceneAt cutsceneAt,
        boolean donateDelivered,
        boolean abandonable,
        @Nullable Pool pool,
        @Nullable TimeWindow timeWindow,
        int hash) {

    public static final String PREFIX = QuestId.PREFIX;
    public static final String MAIN_PREFIX = "quest/main/";

    /**
     * 게시판 후보 (추가 제안 pool).
     *
     * @param board daily · weekly · collect
     * @param region 의뢰가 나오는 지역 (해금한 가장 높은 지역까지만 후보)
     */
    public record Pool(QuestType board, @Nullable RegionId region, int weight) {}

    /** 이벤트 기간 (Asia/Seoul 지역 시각, Core HarborTime 시간대). */
    public record TimeWindow(@Nullable LocalDateTime start, @Nullable LocalDateTime end) {}

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "description",
                    "type",
                    "chapter",
                    "requires",
                    "start_npc",
                    "end_npc",
                    "objectives",
                    "objectives_mode",
                    "rewards",
                    "repeat",
                    "cutscene",
                    "cutscene_at",
                    "donate_delivered",
                    "abandonable",
                    "pool",
                    "time_window");

    public static final ContentType.Entries<QuestDef> TYPE =
            new ContentType.Entries<>(
                    "quest", List.of("quests/*.yml"), PREFIX, QuestDef::parse, q -> q.id().id());

    public QuestDef {
        description = List.copyOf(description);
        objectives = List.copyOf(objectives);
    }

    /** 메인 퀘스트 (시즌 이월, 포기 불가). */
    public boolean main() {
        return type == QuestType.MAIN;
    }

    /** 보고 받는 NPC (없으면 시작 NPC). */
    public Optional<NpcId> turnInNpc() {
        return Optional.ofNullable(endNpc != null ? endNpc : startNpc);
    }

    public Optional<Objective> objective(String key) {
        return objectives.stream().filter(o -> o.key().equals(key)).findFirst();
    }

    public static boolean isMainId(HarborId id) {
        return id.hasPrefix(MAIN_PREFIX);
    }

    static QuestDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("quest/invalid"));
        QuestType type = node.enumValue("type", QuestType.class).orElse(QuestType.REGION);
        if (type == QuestType.MAIN && !isMainId(rawId)) {
            node.error("메인 퀘스트 ID는 quest/main/으로 시작해야 합니다: " + rawId);
        }
        if (type != QuestType.MAIN && isMainId(rawId)) {
            node.error("quest/main/ ID는 type: main이어야 합니다 (시즌 이월 규칙이 ID로 판정됨)");
        }
        int chapter = node.integer("chapter", -1, -1, 8);
        if (type == QuestType.MAIN && chapter < 0) {
            node.error("메인 퀘스트는 chapter(0~8)가 필요합니다");
        }
        Requires requires =
                node.get("requires").map(r -> Requires.parse(r, ctx)).orElse(Requires.NONE);
        NpcId startNpc = npc(node, "start_npc", ctx);
        NpcId endNpc = npc(node, "end_npc", ctx);
        List<Objective> objectives = new ArrayList<>();
        List<YamlNode> objectiveNodes = node.list("objectives");
        if (objectiveNodes.isEmpty()) {
            node.error("objectives가 하나 이상 필요합니다");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < objectiveNodes.size(); i++) {
            Objective o = Objective.parse(objectiveNodes.get(i), i, ctx);
            if (!keys.add(o.key())) {
                objectiveNodes.get(i).error("목표 key가 중복됩니다: " + o.key());
            }
            objectives.add(o);
        }
        ObjectiveMode mode =
                node.has("objectives_mode")
                        ? node.enumValue("objectives_mode", ObjectiveMode.class)
                                .orElse(type.defaultMode())
                        : type.defaultMode();
        if (mode == ObjectiveMode.SEQUENCE) {
            // deliver는 보고할 때 확인하므로 sequence에서는 맨 뒤에만 둔다
            boolean seenDeliver = false;
            for (int i = 0; i < objectives.size(); i++) {
                if (!objectives.get(i).tracked()) {
                    seenDeliver = true;
                } else if (seenDeliver) {
                    objectiveNodes.get(i).error("sequence 모드에서는 deliver 목표를 맨 뒤에 둬야 합니다");
                }
            }
        }
        Rewards rewards =
                node.get("rewards")
                        .map(r -> Rewards.parse(r, ctx, type.board()))
                        .orElse(type.board() ? Rewards.BOARD_DEFAULT : Rewards.NONE);
        Repeat repeat =
                node.has("repeat")
                        ? node.enumValue("repeat", Repeat.class).orElse(type.defaultRepeat())
                        : type.defaultRepeat();
        if (type == QuestType.MAIN && repeat != Repeat.NONE) {
            node.error("메인 퀘스트는 반복할 수 없습니다");
        }
        HarborId cutscene =
                node.get("cutscene")
                        .flatMap(c -> c.asId("cutscene/"))
                        .map(
                                id -> {
                                    ctx.ref("cutscene", id, node.pos());
                                    return id;
                                })
                        .orElse(null);
        CutsceneAt cutsceneAt = cutsceneAt(node, keys);
        boolean abandonable = node.bool("abandonable", type != QuestType.MAIN);
        if (type == QuestType.MAIN && abandonable) {
            node.error("메인 퀘스트는 포기할 수 없습니다 (abandonable: false)");
        }
        Pool pool = node.get("pool").map(p -> pool(p, ctx)).orElse(null);
        if (type.board() && pool == null) {
            node.error(type.key() + " 의뢰는 pool(게시판 후보)이 필요합니다");
        }
        if (pool != null && pool.board() != type) {
            node.error("pool.board(" + pool.board().key() + ")가 type(" + type.key() + ")과 다릅니다");
        }
        TimeWindow window = node.get("time_window").map(QuestDef::window).orElse(null);
        return new QuestDef(
                new QuestId(rawId),
                node.string("name"),
                node.get("description").map(YamlNode::asStringOrList).orElse(List.of()),
                type,
                chapter,
                requires,
                startNpc,
                endNpc,
                objectives,
                mode,
                rewards,
                repeat,
                cutscene,
                cutsceneAt,
                node.bool("donate_delivered", false),
                abandonable,
                pool,
                window,
                hash(objectives));
    }

    private static @Nullable NpcId npc(YamlNode node, String key, ParseContext ctx) {
        return node.get(key)
                .flatMap(n -> n.asId(NpcId.PREFIX))
                .map(
                        id -> {
                            ctx.ref("npc", id, node.pos());
                            return new NpcId(id);
                        })
                .orElse(null);
    }

    private static CutsceneAt cutsceneAt(YamlNode node, Set<String> keys) {
        String raw = node.string("cutscene_at", "complete");
        if (raw.equals("accept")) {
            return new CutsceneAt(CutsceneAt.Kind.ACCEPT, null);
        }
        if (raw.equals("complete")) {
            return CutsceneAt.COMPLETE;
        }
        if (raw.startsWith("objective:")) {
            String key = raw.substring("objective:".length());
            if (!keys.contains(key)) {
                node.error("cutscene_at의 목표 키가 없습니다: " + key);
            }
            return new CutsceneAt(CutsceneAt.Kind.OBJECTIVE, key);
        }
        node.error("cutscene_at은 accept · complete · objective:<key>여야 합니다: " + raw);
        return CutsceneAt.COMPLETE;
    }

    private static Pool pool(YamlNode p, ParseContext ctx) {
        p.allowOnly(Set.of("board", "region", "weight"));
        QuestType board = p.enumValue("board", QuestType.class).orElse(QuestType.DAILY);
        if (!board.board()) {
            p.error("pool.board는 daily · weekly · collect여야 합니다");
        }
        RegionId region =
                p.get("region")
                        .flatMap(r -> r.asId(RegionId.PREFIX))
                        .map(
                                id -> {
                                    ctx.ref("region", id, p.pos());
                                    return new RegionId(id);
                                })
                        .orElse(null);
        return new Pool(board, region, p.integer("weight", 100, 1, 1_000_000));
    }

    private static TimeWindow window(YamlNode w) {
        w.allowOnly(Set.of("start", "end"));
        LocalDateTime start = w.optString("start").map(s -> time(w, s)).orElse(null);
        LocalDateTime end = w.optString("end").map(s -> time(w, s)).orElse(null);
        if (start != null && end != null && !end.isAfter(start)) {
            w.error("time_window.end가 start보다 뒤여야 합니다");
        }
        return new TimeWindow(start, end);
    }

    private static @Nullable LocalDateTime time(YamlNode w, String raw) {
        try {
            return raw.length() == 10
                    ? LocalDateTime.parse(raw + "T00:00:00")
                    : LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            w.error("시각 형식이 틀렸습니다 (2027-05-01 또는 2027-05-01T18:00): " + raw);
            return null;
        }
    }

    /** 목표 구성 해시 (key·type·대상·수량). */
    static int hash(List<Objective> objectives) {
        StringBuilder sb = new StringBuilder();
        for (Objective o : objectives) {
            sb.append(o.key())
                    .append('|')
                    .append(o.type())
                    .append('|')
                    .append(o.target().raw())
                    .append('|')
                    .append(o.count())
                    .append(';');
        }
        return sb.toString().hashCode();
    }
}
