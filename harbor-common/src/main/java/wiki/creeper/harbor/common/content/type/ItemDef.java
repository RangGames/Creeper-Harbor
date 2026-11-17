package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** 아이템 정의 (items/*.yml). 필드는 '11. 데이터 설계' 2.2를 따른다. */
public record ItemDef(
        ItemId id,
        String name,
        List<String> lore,
        Type type,
        Grade grade,
        String baseItem,
        String model,
        Map<String, Double> stats,
        List<RegionId> regionAccess,
        OptionalInt buyPrice,
        OptionalInt sellPrice,
        List<NpcId> shop,
        boolean tradeable,
        boolean seasonReset) {

    /** 아이템 분류. */
    public enum Type implements Keyed {
        ROD,
        LINE,
        FLOAT,
        REEL,
        BAIT,
        TRAP,
        BOAT,
        TOOL,
        GEAR,
        FOOD,
        MATERIAL,
        CURRENCY,
        TOKEN;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 바닐라 아이템 키 형식 (예: minecraft:fishing_rod). 실제 존재 여부는 Core가 서버 레지스트리로 검사한다. */
    private static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Set<String> FIELDS =
            Set.of(
                    "id", "name", "lore", "type", "grade", "base_item", "model", "stats",
                    "region_access", "buy_price", "sell_price", "shop", "tradeable", "season_reset");

    public static final ContentType.Entries<ItemDef> TYPE =
            new ContentType.Entries<>(
                    "item", List.of("items/*.yml"), ItemId.PREFIX, ItemDef::parse, i -> i.id().id());

    static ItemDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId = node.require("id").flatMap(n -> n.asId(ItemId.PREFIX)).orElse(Placeholder.ITEM);

        String baseItem = node.string("base_item");
        if (!baseItem.isEmpty() && !RESOURCE_KEY.matcher(baseItem).matches()) {
            node.error("base_item은 'minecraft:fishing_rod' 같은 키여야 합니다: '" + baseItem + "'");
        }

        Map<String, Double> stats = new LinkedHashMap<>();
        node.get("stats").ifPresent(s -> s.entries().forEach((k, v) -> stats.put(k, v.asDouble())));

        return new ItemDef(
                new ItemId(rawId),
                node.string("name"),
                node.get("lore").map(YamlNode::asStringOrList).orElse(List.of()),
                node.enumValue("type", Type.class).orElse(Type.MATERIAL),
                node.enumValue("grade", Grade.class).orElse(Grade.COMMON),
                baseItem,
                node.string("model"),
                Map.copyOf(stats),
                Refs.regions(node.list("region_access"), ctx),
                price(node, "buy_price"),
                price(node, "sell_price"),
                Refs.npcs(node.list("shop"), ctx),
                node.bool("tradeable"),
                node.bool("season_reset"));
    }

    private static OptionalInt price(YamlNode node, String key) {
        if (!node.has(key)) {
            return OptionalInt.empty();
        }
        int value = node.integer(key);
        if (value < 0) {
            node.error(key + "는 0 이상이어야 합니다");
        }
        return OptionalInt.of(value);
    }
}
