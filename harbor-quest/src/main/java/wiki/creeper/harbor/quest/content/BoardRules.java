package wiki.creeper.harbor.quest.content;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 어부 길드 게시판 (board.yml, 추가 제안, docs 20 6.3). 수치는 '13. 제작 · 상점 가격' 6장과 '7. 밸런스 기획' 7장.
 *
 * @param slots 게시판 종류별 규칙 (daily · weekly · collect)
 * @param ladder 지역 해금 순서와 그 지역이 가장 높을 때의 의뢰 골드. 해금한 가장 높은 지역 판정에도 쓴다
 * @param location 게시판 Interaction 위치 (없으면 /의뢰 명령으로만 연다)
 * @param collectNpc 수집 의뢰를 주는 NPC (수족관 관장 이슬). quest:list에 수집 의뢰 게시판을 붙인다
 */
public record BoardRules(
        Map<QuestType, Slot> slots,
        List<RegionGold> ladder,
        int hintCount,
        long hintFallbackGold,
        @Nullable Location location,
        @Nullable NpcId collectNpc) {

    /**
     * @param offers 게시하는 후보 수
     * @param pick 수락할 수 있는 수 (일일 1, 주간 3, 수집 2)
     */
    public record Slot(int offers, int pick, long mulbineul, int reputation) {}

    /** 지역 ID는 World가 regions.yml을 채우기 전에도 쓸 수 있게 참조 검사를 하지 않는다 (없는 지역은 해금 판정에서 빠짐). */
    public record RegionGold(RegionId region, long daily, long weekly) {}

    public record Location(String world, double x, double y, double z, float yaw) {}

    public static final ContentType.Single<BoardRules> TYPE =
            new ContentType.Single<>("quest_board", "board.yml", false, BoardRules::parse);

    public BoardRules {
        slots = Map.copyOf(slots);
        ladder = List.copyOf(ladder);
    }

    public Slot slot(QuestType kind) {
        Slot s = slots.get(kind);
        return s != null ? s : new Slot(3, 1, 0, 0);
    }

    /** 지역 순서 (해금 순서, 0부터). 표에 없으면 -1. */
    public int rank(RegionId region) {
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).region().equals(region)) {
                return i;
            }
        }
        return -1;
    }

    public Optional<RegionGold> gold(@Nullable RegionId highest) {
        if (highest == null) {
            return ladder.isEmpty() ? Optional.empty() : Optional.of(ladder.getFirst());
        }
        return ladder.stream().filter(g -> g.region().equals(highest)).findFirst();
    }

    static BoardRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "daily",
                        "weekly",
                        "collect",
                        "gold_by_region",
                        "hint_count",
                        "hint_fallback_gold",
                        "location",
                        "collect_npc"));
        Map<QuestType, Slot> slots = new EnumMap<>(QuestType.class);
        slots.put(QuestType.DAILY, slot(node, "daily", 3, 1, 1, 10));
        slots.put(QuestType.WEEKLY, slot(node, "weekly", 3, 3, 1, 30));
        slots.put(QuestType.COLLECT, slot(node, "collect", 2, 2, 0, 20));
        List<RegionGold> ladder = new ArrayList<>();
        for (YamlNode g : node.list("gold_by_region")) {
            g.allowOnly(Set.of("region", "daily", "weekly"));
            HarborId id =
                    g.require("region")
                            .flatMap(r -> r.asId(RegionId.PREFIX))
                            .orElse(HarborId.parse("region/invalid"));
            ladder.add(
                    new RegionGold(
                            new RegionId(id), g.longValue("daily", 0), g.longValue("weekly", 0)));
        }
        Location location =
                node.get("location")
                        .map(
                                l -> {
                                    l.allowOnly(Set.of("world", "x", "y", "z", "yaw"));
                                    return new Location(
                                            l.string("world", "harbor_main"),
                                            l.number("x"),
                                            l.number("y"),
                                            l.number("z"),
                                            (float) l.number("yaw", 0));
                                })
                        .orElse(null);
        NpcId collectNpc =
                node.get("collect_npc")
                        .flatMap(n -> n.asId(NpcId.PREFIX))
                        .map(
                                id -> {
                                    ctx.ref("npc", id, node.pos());
                                    return new NpcId(id);
                                })
                        .orElse(null);
        return new BoardRules(
                slots,
                ladder,
                node.integer("hint_count", 1, 0, 10),
                node.longValue("hint_fallback_gold", 100),
                location,
                collectNpc);
    }

    private static Slot slot(
            YamlNode node, String key, int offers, int pick, long mulbineul, int reputation) {
        Optional<YamlNode> s = node.get(key);
        if (s.isEmpty()) {
            return new Slot(offers, pick, mulbineul, reputation);
        }
        YamlNode n = s.get();
        n.allowOnly(Set.of("offers", "pick", "mulbineul", "reputation"));
        int o = n.integer("offers", offers, 1, 9);
        int p = n.integer("pick", pick, 1, 9);
        if (p > o) {
            n.error("pick이 offers보다 클 수 없습니다");
        }
        return new Slot(
                o, p, n.longValue("mulbineul", mulbineul), n.integer("reputation", reputation));
    }
}
