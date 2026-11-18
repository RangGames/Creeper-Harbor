package wiki.creeper.harbor.shop.content;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Tebex 패키지 ↔ 지급 내용 매핑 (shop/products.yml, docs 20 12.3). 충전은 10원 = 진주 1개 고정, 보너스 없음 ('6. BM 기획'
 * 2장).
 *
 * @param tebexPackageId Tebex 패키지 번호 (웹훅 subject.products[].id)
 * @param pearls 지급 진주 (pearls). pearls_custom은 결제액 ÷ 10, subscription은 월 진주
 * @param tier 후원 등급 (subscription)
 * @param priceKrw 상품 가격 (원). 결제 금액과 다르면 HELD. pearls_custom·festival은 0 (자유 금액)
 * @param festival 축제 후원 패키지가 더하는 축제 (festival)
 */
public record ProductDef(
        HarborId id,
        long tebexPackageId,
        Kind kind,
        long pearls,
        DonorTier tier,
        long priceKrw,
        @Nullable HarborId festival) {

    /** 상품 종류. */
    public enum Kind implements Keyed {
        PEARLS,
        PEARLS_CUSTOM,
        SUBSCRIPTION,
        FESTIVAL;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    public static final String PREFIX = "product/";

    /** 10원 = 진주 1개 ('6. BM 기획' 2장 충전 규칙, 고정값). */
    public static final long KRW_PER_PEARL = 10;

    public static final ContentType.Entries<ProductDef> TYPE =
            new ContentType.Entries<>(
                    "shop_product",
                    List.of("shop/products.yml"),
                    PREFIX,
                    ProductDef::parse,
                    ProductDef::id);

    /** 고정 가격 상품인지 (결제 금액과 상품 가격이 같아야 함). */
    public boolean fixedPrice() {
        return kind == Kind.PEARLS || kind == Kind.SUBSCRIPTION;
    }

    static ProductDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "tebex_package_id",
                        "type",
                        "pearls",
                        "tier",
                        "price_krw",
                        "festival"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("product/invalid"));
        Kind kind = node.enumValue("type", Kind.class).orElse(Kind.PEARLS);
        long packageId = node.longValue("tebex_package_id", 0);
        if (packageId <= 0) {
            node.error("tebex_package_id가 있어야 합니다 (양의 정수)");
        }
        long pearls = node.longValue("pearls", 0);
        long price = node.longValue("price_krw", 0);
        DonorTier tier = DonorTier.parse(node.optString("tier").orElse(null));
        HarborId festival = null;
        switch (kind) {
            case PEARLS -> {
                if (pearls <= 0 || price <= 0) {
                    node.error("pearls 상품은 pearls와 price_krw가 있어야 합니다");
                } else if (pearls * KRW_PER_PEARL != price) {
                    // 충전 보너스 없음 ('6. BM 기획' 2장): 10원 = 진주 1개 고정
                    node.error("진주 상품은 price_krw = pearls × 10이어야 합니다 (보너스 없음)");
                }
            }
            case SUBSCRIPTION -> {
                if (tier == DonorTier.NONE || price <= 0) {
                    node.error("subscription 상품은 tier(harbor · fleet)와 price_krw가 있어야 합니다");
                }
            }
            case FESTIVAL -> {
                YamlNode f = node.require("festival").orElse(node);
                festival = f.asId(FestivalDef.PREFIX).orElse(null);
                if (festival != null) {
                    ctx.ref(FestivalDef.TYPE.name(), festival, f.pos());
                }
            }
            case PEARLS_CUSTOM -> {}
        }
        return new ProductDef(id, packageId, kind, pearls, tier, price, festival);
    }
}
