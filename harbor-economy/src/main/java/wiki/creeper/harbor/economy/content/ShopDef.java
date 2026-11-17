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
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * NPC 상점 (shops.yml, 추가 제안, docs 20 7.3). 가격은 항목의 price가 없으면 아이템의 buy_price.
 *
 * @param npc 상점 NPC (NPC 모듈이 클릭 때 연다)
 */
public record ShopDef(HarborId id, @Nullable NpcId npc, String title, List<Entry> entries) {

    /**
     * 판매 항목.
     *
     * @param dailyLimit 1인 하루 구매 한도 (0이면 없음)
     * @param jobTier 필요한 직업 단계 (0이면 없음)
     */
    public record Entry(
            ItemId item, OptionalLong price, int dailyLimit, int jobTier, @Nullable JobLine line) {}

    public static final String PREFIX = "shop/";

    public static final ContentType.Entries<ShopDef> TYPE =
            new ContentType.Entries<>(
                    "shop", List.of("shops.yml"), PREFIX, ShopDef::parse, ShopDef::id);

    /** 가격이 정해지지 않은 항목 (price도 buy_price도 없음)은 오류. */
    public static final ContentCheck<ShopDef> PRICE_CHECK =
            (shop, check) -> {
                for (Entry entry : shop.entries()) {
                    boolean priced =
                            entry.price().isPresent()
                                    || check.snapshot()
                                            .get(ItemDef.TYPE, entry.item().id())
                                            .map(d -> d.buyPrice().isPresent())
                                            .orElse(true);
                    if (!priced) {
                        check.error(
                                "상점 항목 " + entry.item() + "에 가격이 없습니다 (price 또는 아이템 buy_price)");
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
            e.allowOnly(Set.of("item", "price", "daily_limit", "requires"));
            HarborId item =
                    e.require("item")
                            .flatMap(n -> n.asId(ItemId.PREFIX))
                            .orElse(HarborId.parse("item/invalid"));
            e.get("item").ifPresent(n -> ctx.ref("item", item, n.pos()));
            int tier = 0;
            JobLine line = null;
            if (e.has("requires")) {
                YamlNode r = e.get("requires").orElseThrow();
                r.allowOnly(Set.of("job_tier", "line"));
                tier = r.integer("job_tier", 0, 0, 3);
                line = r.has("line") ? r.enumValue("line", JobLine.class).orElse(null) : null;
            }
            OptionalLong price =
                    e.has("price")
                            ? OptionalLong.of(e.longValue("price", 0))
                            : OptionalLong.empty();
            entries.add(
                    new Entry(
                            new ItemId(item),
                            price,
                            e.integer("daily_limit", 0, 0, 100_000),
                            tier,
                            line));
        }
        return new ShopDef(id, npc, node.string("title"), List.copyOf(entries));
    }
}
