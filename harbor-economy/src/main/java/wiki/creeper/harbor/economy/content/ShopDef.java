package wiki.creeper.harbor.economy.content;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * NPC 상점 (shops.yml, 추가 제안, docs 20 7.3). 가격은 항목의 price가 없으면 아이템의 buy_price. 바닐라 재료(철 주괴·화약 …)는
 * {@code vanilla:}로 쓰고 price가 필수다 ('13. 제작 · 상점 가격' 4장 고정가).
 *
 * @param npc 상점 NPC (대화 액션 shop:open이 이 NPC의 상점을 연다)
 */
public record ShopDef(HarborId id, @Nullable NpcId npc, String title, List<Entry> entries) {

    /**
     * 판매 항목. item과 vanilla 중 하나만 있다.
     *
     * @param dailyLimit 1인 하루 구매 한도 (0이면 없음)
     * @param jobTier 필요한 직업 단계 (0이면 없음)
     * @param visited 지역 첫 방문 (플레이어 플래그 visited/<지역>, 추가 제안)
     * @param quest 퀘스트 완료 (플레이어 플래그 quest/<경로>, 추가 제안)
     */
    public record Entry(
            @Nullable ItemId item,
            @Nullable String vanilla,
            OptionalLong price,
            int dailyLimit,
            int jobTier,
            @Nullable JobLine line,
            @Nullable RegionId visited,
            @Nullable QuestId quest) {

        /** 한도 표에 쓰는 항목 키. */
        public String key() {
            ItemId it = item;
            return it != null ? it.id().path() : "vanilla/" + String.valueOf(vanilla);
        }

        /** 확인할 플레이어 플래그. */
        public List<String> requiredFlags() {
            List<String> flags = new ArrayList<>();
            RegionId v = visited;
            if (v != null) {
                flags.add(RecipeDef.visitedFlag(v));
            }
            QuestId q = quest;
            if (q != null) {
                flags.add(RecipeDef.questFlag(q));
            }
            return flags;
        }
    }

    public static final String PREFIX = "shop/";

    public static final ContentType.Entries<ShopDef> TYPE =
            new ContentType.Entries<>(
                    "shop", List.of("shops.yml"), PREFIX, ShopDef::parse, ShopDef::id);

    /** 가격이 정해지지 않은 항목 (price도 buy_price도 없음)은 오류. */
    public static final ContentCheck<ShopDef> PRICE_CHECK =
            (shop, check) -> {
                for (Entry entry : shop.entries()) {
                    ItemId item = entry.item();
                    boolean priced =
                            entry.price().isPresent()
                                    || (item != null
                                            && check.snapshot()
                                                    .get(ItemDef.TYPE, item.id())
                                                    .map(d -> d.buyPrice().isPresent())
                                                    .orElse(true));
                    if (!priced) {
                        check.error("상점 항목 " + entry.key() + "에 가격이 없습니다 (price 또는 아이템 buy_price)");
                    }
                }
            };

    static ShopDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "npc", "title", "entries"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("shop/invalid"));
        NpcId npc = null;
        if (node.has("npc")) {
            YamlNode n = node.get("npc").orElseThrow();
            HarborId npcId = n.asId(NpcId.PREFIX).orElse(null);
            if (npcId != null) {
                npc = new NpcId(npcId);
                ctx.ref("npc", npcId, n.pos());
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (YamlNode e : node.list("entries")) {
            e.allowOnly(Set.of("item", "vanilla", "price", "daily_limit", "requires"));
            ItemId item = null;
            String vanilla = null;
            if (e.has("item") == e.has("vanilla")) {
                e.error("항목에는 item과 vanilla 중 하나만 써야 합니다");
                continue;
            }
            if (e.has("item")) {
                YamlNode n = e.get("item").orElseThrow();
                HarborId raw = n.asId(ItemId.PREFIX).orElse(null);
                if (raw == null) {
                    continue;
                }
                ctx.ref("item", raw, n.pos());
                item = new ItemId(raw);
            } else {
                YamlNode n = e.get("vanilla").orElseThrow();
                vanilla = n.asString();
                if (!RecipeDef.RESOURCE_KEY.matcher(vanilla).matches()
                        || vanilla.startsWith("harbor:")) {
                    n.error("바닐라 아이템 키 형식이 틀렸습니다 (예: minecraft:iron_ingot): '" + vanilla + "'");
                    continue;
                }
                if (!e.has("price")) {
                    e.error("바닐라 항목은 price가 필요합니다");
                }
            }
            int tier = 0;
            JobLine line = null;
            RegionId visited = null;
            QuestId quest = null;
            if (e.has("requires")) {
                YamlNode r = e.get("requires").orElseThrow();
                r.allowOnly(Set.of("job_tier", "line", "visited", "quest"));
                tier = r.integer("job_tier", 0, 0, 3);
                line = r.has("line") ? r.enumValue("line", JobLine.class).orElse(null) : null;
                if (r.has("visited")) {
                    YamlNode v = r.get("visited").orElseThrow();
                    HarborId rid = v.asId(RegionId.PREFIX).orElse(null);
                    if (rid != null) {
                        ctx.ref("region", rid, v.pos());
                        visited = new RegionId(rid);
                    }
                }
                if (r.has("quest")) {
                    YamlNode q = r.get("quest").orElseThrow();
                    HarborId qid = q.asId(QuestId.PREFIX).orElse(null);
                    if (qid != null) {
                        ctx.ref("quest", qid, q.pos());
                        quest = new QuestId(qid);
                    }
                }
            }
            OptionalLong price =
                    e.has("price")
                            ? OptionalLong.of(e.longValue("price", 0))
                            : OptionalLong.empty();
            if (price.isPresent() && price.getAsLong() <= 0) {
                e.error("price는 0보다 커야 합니다");
            }
            entries.add(
                    new Entry(
                            item,
                            vanilla,
                            price,
                            e.integer("daily_limit", 0, 0, 100_000),
                            tier,
                            line,
                            visited,
                            quest));
        }
        return new ShopDef(id, npc, node.string("title"), List.copyOf(entries));
    }
}
