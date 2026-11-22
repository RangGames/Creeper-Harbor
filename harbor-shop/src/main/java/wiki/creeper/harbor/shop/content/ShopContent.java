package wiki.creeper.harbor.shop.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.id.HarborId;

/** Shop content types. Uses no server API, so harbor-tools (CI) validates with the same code. */
public final class ShopContent {

    public static final List<ContentType<?>> TYPES =
            List.of(
                    CosmeticDef.TYPE,
                    CatchFxPreset.TYPE,
                    ProductDef.TYPE,
                    RotationPlan.TYPE,
                    PassDef.TYPE,
                    ChestDef.TYPE,
                    BundleDef.TYPE,
                    FestivalDef.TYPE,
                    CreatorDef.TYPE,
                    ShopSettings.TYPE);

    /** Duplicate Tebex package numbers make the webhook mapping ambiguous. */
    public static final ContentCheck<ProductDef> PRODUCT_UNIQUE =
            (product, check) -> {
                long same =
                        check.snapshot().all(ProductDef.TYPE).values().stream()
                                .filter(p -> p.tebexPackageId() == product.tebexPackageId())
                                .count();
                if (same > 1) {
                    check.error("tebex_package_id가 겹칩니다: " + product.tebexPackageId());
                }
            };

    /**
     * Chest entries must be chest-only (availability: chest) cosmetics, and their grade must match
     * the cosmetic's grade.
     */
    public static final ContentCheck<ChestDef> CHEST_ENTRIES =
            (chest, check) -> {
                for (ChestDef.Entry entry : chest.entries()) {
                    check.snapshot()
                            .get(CosmeticDef.TYPE, entry.cosmetic())
                            .ifPresent(
                                    c -> {
                                        if (c.grade() != entry.grade()) {
                                            check.error(
                                                    entry.cosmetic()
                                                            + " 등급이 코스메틱 정의("
                                                            + c.grade().key()
                                                            + ")와 다릅니다");
                                        }
                                        if (c.availability() != CosmeticDef.Availability.CHEST) {
                                            check.error(
                                                    entry.cosmetic()
                                                            + "는 availability: chest여야 합니다 (상점 판매품을 확률형에 넣지 않음)");
                                        }
                                    });
                }
            };

    /** Duplicate creator codes make payouts ambiguous. */
    public static final ContentCheck<CreatorDef> CREATOR_UNIQUE =
            (creator, check) -> {
                long same =
                        check.snapshot().all(CreatorDef.TYPE).values().stream()
                                .filter(c -> c.code().equals(creator.code()))
                                .count();
                if (same > 1) {
                    check.error("크리에이터 코드가 겹칩니다: " + creator.code());
                }
            };

    /** Two passes for the same season make it ambiguous which to use. */
    public static final ContentCheck<PassDef> PASS_UNIQUE =
            (pass, check) -> {
                long same =
                        check.snapshot().all(PassDef.TYPE).values().stream()
                                .filter(p -> p.season() == pass.season())
                                .count();
                if (same > 1) {
                    check.error("시즌 " + pass.season() + " 패스가 둘 이상입니다");
                }
            };

    /** Registers all checks (shared by the plugin and harbor-tools). */
    public static void registerChecks(Registrar registrar) {
        registrar.add(ProductDef.TYPE, PRODUCT_UNIQUE);
        registrar.add(ChestDef.TYPE, CHEST_ENTRIES);
        registrar.add(CreatorDef.TYPE, CREATOR_UNIQUE);
        registrar.add(PassDef.TYPE, PASS_UNIQUE);
    }

    /** Check registration hook (Core ModuleContext.contentCheck or ContentLoader.addCheck). */
    public interface Registrar {
        <T> void add(ContentType<T> type, ContentCheck<T> check);
    }

    /** For displaying ID strings (without the harbor: prefix). */
    public static String shortId(HarborId id) {
        return id.path();
    }

    private ShopContent() {}
}
