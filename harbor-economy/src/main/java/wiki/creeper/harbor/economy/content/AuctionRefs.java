package wiki.creeper.harbor.economy.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Auction reference price table (auction_refs.yml, proposed). The documented items/*.yml
 * auction_ref field (docs 20 7.3, "11. Data design" 2.9) is not in Common ItemDef yet, so those
 * values are gathered in this file. Used as the reference price for tradable items without
 * sell_price or buy_price.
 *
 * <pre>{@code
 * refs:
 *   item/bait/legendary: 20000
 * }</pre>
 */
public record AuctionRefs(Map<ItemId, Long> refs) {

    public static final AuctionRefs EMPTY = new AuctionRefs(Map.of());

    public static final ContentType.Single<AuctionRefs> TYPE =
            new ContentType.Single<>("auction_refs", "auction_refs.yml", false, AuctionRefs::parse);

    public OptionalLong of(ItemId item) {
        Long value = refs.get(item);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    static AuctionRefs parse(YamlNode node, ParseContext ctx) {
        if (node.isNull()) {
            return EMPTY;
        }
        node.allowOnly(Set.of("refs"));
        Map<ItemId, Long> refs = new LinkedHashMap<>();
        node.get("refs")
                .ifPresent(
                        map ->
                                map.entries()
                                        .forEach(
                                                (key, value) -> {
                                                    HarborId id =
                                                            HarborId.tryParse(key).orElse(null);
                                                    if (id == null
                                                            || !id.hasPrefix(ItemId.PREFIX)) {
                                                        value.error("아이템 ID가 아닙니다: '" + key + "'");
                                                        return;
                                                    }
                                                    long price = value.asLong();
                                                    if (price <= 0) {
                                                        value.error("기준가는 0보다 커야 합니다");
                                                        return;
                                                    }
                                                    ctx.ref("item", id, value.pos());
                                                    refs.put(new ItemId(id), price);
                                                }));
        return new AuctionRefs(Map.copyOf(refs));
    }
}
