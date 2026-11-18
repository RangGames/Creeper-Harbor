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
 * 경매 기준가 표 (auction_refs.yml, 추가 제안). 문서의 items/*.yml auction_ref 필드(docs 20 7.3, '11. 데이터 설계'
 * 2.9)는 Common ItemDef에 아직 없어서, 그 값을 이 파일에 모은다. sell_price·buy_price가 없는 거래 가능 아이템의 기준가로 쓴다.
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
