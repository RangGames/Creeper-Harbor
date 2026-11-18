package wiki.creeper.harbor.shop.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 코스메틱 묶음 진열 (shop/bundles.yml, 추가 제안). 「첫 항해 패키지」처럼 진주로 사는 입문 코스메틱 묶음 ('6. BM 기획' 4장: 계정당 1회, 진주
 * 추가 지급 없음). docs 20 12.4의 account_limit(추가 제안)으로 두 번째 구매를 막는다 (shop_order 기록).
 *
 * @param accountLimit 계정당 구매 한도 (0이면 없음)
 * @param weeks 파는 시즌 주차 (비우면 상시). 첫 항해 패키지는 1~2주차
 * @param season 파는 시즌 (0이면 모든 시즌)
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

    /** 이 시즌·주차에 진열하는지. */
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
