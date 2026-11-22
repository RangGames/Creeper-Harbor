package wiki.creeper.harbor.shop.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Cosmetic bundle listing (shop/bundles.yml, proposed). Starter cosmetic bundles bought with
 * pearls, like the "First Voyage Pack" ('6. BM plan' ch. 4: once per account, no extra pearls).
 * account_limit from docs 20 12.4 (proposed) blocks a second purchase (shop_order records).
 *
 * @param accountLimit purchase limit per account (0 means none)
 * @param weeks season weeks it is sold (empty means always). The First Voyage Pack is weeks 1–2
 * @param season season it is sold (0 means every season)
 */
public record BundleDef(
        HarborId id,
        String name,
        List<String> lore,
        String icon,
        int price,
        List<HarborId> items,
        int accountLimit,
        List<Integer> weeks,
        int season) {

    public static final String PREFIX = "bundle/";

    public static final ContentType.Entries<BundleDef> TYPE =
            new ContentType.Entries<>(
                    "shop_bundle",
                    List.of("shop/bundles.yml"),
                    PREFIX,
                    BundleDef::parse,
                    BundleDef::id);

    /** Whether it is listed in this season and week. */
    public boolean onSale(int currentSeason, int week) {
        return (season == 0 || season == currentSeason)
                && (weeks.isEmpty() || weeks.contains(week));
    }

    static BundleDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "name",
                        "lore",
                        "icon",
                        "price",
                        "items",
                        "account_limit",
                        "weeks",
                        "season"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("bundle/invalid"));
        List<HarborId> items = new ArrayList<>();
        for (YamlNode i : node.list("items")) {
            i.asId(CosmeticDef.PREFIX)
                    .ifPresent(
                            c -> {
                                items.add(c);
                                ctx.ref(CosmeticDef.TYPE.name(), c, i.pos());
                            });
        }
        if (items.isEmpty()) {
            node.error("items가 비어 있습니다");
        }
        List<Integer> weeks = node.list("weeks").stream().map(YamlNode::asInt).toList();
        return new BundleDef(
                id,
                node.string("name"),
                node.get("lore").map(YamlNode::asStringOrList).orElse(List.of()),
                node.string("icon", "minecraft:chest"),
                node.integer("price", 0, 1, 100_000),
                List.copyOf(items),
                node.integer("account_limit", 0, 0, 100),
                weeks,
                node.integer("season", 0, 0, 1000));
    }
}
