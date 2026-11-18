package wiki.creeper.harbor.shop.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.id.HarborId;

/** Shop 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
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

    /** Tebex 패키지 번호가 겹치면 웹훅 매핑이 모호해진다. */
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

    /** 보물함 구성품은 보물함 전용(availability: chest) 코스메틱이어야 하고, 등급은 코스메틱 등급과 같아야 한다. */
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

    /** 크리에이터 코드가 겹치면 정산이 모호해진다. */
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

    /** 같은 시즌의 패스가 둘이면 어느 쪽을 쓸지 모호하다. */
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

    /** 모든 검사를 등록한다 (플러그인과 harbor-tools가 같이 쓴다). */
    public static void registerChecks(Registrar registrar) {
        registrar.add(ProductDef.TYPE, PRODUCT_UNIQUE);
        registrar.add(ChestDef.TYPE, CHEST_ENTRIES);
        registrar.add(CreatorDef.TYPE, CREATOR_UNIQUE);
        registrar.add(PassDef.TYPE, PASS_UNIQUE);
    }

    /** 검사 등록 창구 (Core ModuleContext.contentCheck 또는 ContentLoader.addCheck). */
    public interface Registrar {
        <T> void add(ContentType<T> type, ContentCheck<T> check);
    }

    /** ID 문자열 표시용 (harbor: 접두사 생략). */
    public static String shortId(HarborId id) {
        return id.path();
    }

    private ShopContent() {}
}
