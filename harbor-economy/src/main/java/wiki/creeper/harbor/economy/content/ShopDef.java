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
 * NPC shop (shops.yml, proposed, docs 20 7.3). The price is the entry's price, or the item's
 * buy_price if absent. Vanilla materials (iron ingot, gunpowder …) are written as {@code vanilla:}
 * and require a price ("13. Crafting · Shop prices" ch. 4 fixed prices).
 *
 * @param npc shop NPC (the shop:open dialog action opens this NPC's shop)
 */
public record ShopDef(HarborId id, @Nullable NpcId npc, String title, List<Entry> entries) {

    /**
     * Shop entry. Exactly one of item and vanilla is present.
     *
     * @param dailyLimit per-player daily purchase limit (0 for none)
     * @param jobTier required job tier (0 for none)
     * @param visited first visit to a region (player flag visited/<region>, proposed)
     * @param quest quest completed (player flag quest/<path>, proposed)
     * @param flags other player flags that must all be set, e.g. story/ch4_done (proposed)
     */
    public record Entry(
            @Nullable ItemId item,
            @Nullable String vanilla,
            OptionalLong price,
            int dailyLimit,
            int jobTier,
            @Nullable JobLine line,
            @Nullable RegionId visited,
            @Nullable QuestId quest,
            List<String> flags) {

        public Entry {
            flags = List.copyOf(flags);
        }

        /** Entry key used in the limit table. */
        public String key() {
            ItemId it = item;
            return it != null ? it.id().path() : "vanilla/" + String.valueOf(vanilla);
        }

        /** Player flags to check. */
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
            flags.addAll(this.flags);
            return flags;
        }
    }

    public static final String PREFIX = "shop/";

    public static final ContentType.Entries<ShopDef> TYPE =
            new ContentType.Entries<>(
                    "shop", List.of("shops.yml"), PREFIX, ShopDef::parse, ShopDef::id);

    /** An entry with no price defined (neither price nor buy_price) is an error. */
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
            List<String> flags = new ArrayList<>();
            if (e.has("requires")) {
                YamlNode r = e.get("requires").orElseThrow();
                r.allowOnly(Set.of("job_tier", "line", "visited", "quest", "flags"));
                for (String flag : r.strings("flags")) {
                    if (flag.matches("[a-z0-9_]+(/[a-z0-9_]+)*")) {
                        flags.add(flag);
                    } else {
                        r.error("플래그 이름 형식이 틀렸습니다: '" + flag + "'");
                    }
                }
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
                            quest,
                            flags));
        }
        return new ShopDef(id, npc, node.string("title"), List.copyOf(entries));
    }
}
