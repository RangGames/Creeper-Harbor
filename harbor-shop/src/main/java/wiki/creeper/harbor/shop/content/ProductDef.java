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
 * Tebex package ↔ grant mapping (shop/products.yml, docs 20 12.3). Top-ups are fixed at 10 KRW = 1
 * pearl, no bonus ('6. BM plan' ch. 2).
 *
 * @param tebexPackageId Tebex package number (webhook subject.products[].id)
 * @param pearls pearls granted (pearls). pearls_custom is payment amount ÷ 10, subscription is
 *     monthly pearls
 * @param tier donor tier (subscription)
 * @param priceKrw product price (KRW). HELD if it differs from the paid amount. 0 for pearls_custom
 *     and festival (free amount)
 * @param festival festival that a festival sponsorship package contributes to (festival)
 */
public record ProductDef(
        HarborId id,
        long tebexPackageId,
        Kind kind,
        long pearls,
        DonorTier tier,
        long priceKrw,
        @Nullable HarborId festival) {

    /** Product kind. */
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

    /** 10 KRW = 1 pearl ('6. BM plan' ch. 2 top-up rule, fixed value). */
    public static final long KRW_PER_PEARL = 10;

    public static final ContentType.Entries<ProductDef> TYPE =
            new ContentType.Entries<>(
                    "shop_product",
                    List.of("shop/products.yml"),
                    PREFIX,
                    ProductDef::parse,
                    ProductDef::id);

    /** Whether this is a fixed-price product (paid amount must equal the product price). */
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
                    // No top-up bonus ('6. BM plan' ch. 2): fixed at 10 KRW = 1 pearl
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
