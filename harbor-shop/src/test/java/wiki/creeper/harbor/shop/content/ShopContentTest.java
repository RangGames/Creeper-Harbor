package wiki.creeper.harbor.shop.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.content.SeasonContent;
import wiki.creeper.harbor.shop.pass.PassMath;

class ShopContentTest {

    private static final Path CONTENT = Path.of("..", "content");

    private static YamlNode node(String yaml, ValidationErrors errors) {
        return YamlNode.parse("test.yml", new StringReader(yaml), errors).orElseThrow();
    }

    @Test
    void sampleContentLoadsWithCoreLoader() {
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        SeasonContent.TYPES.forEach(loader::register);
        ShopContent.TYPES.forEach(loader::register);
        ShopContent.registerChecks(loader::addCheck);
        Result<ContentSnapshot, ValidationErrors> result = loader.load(CONTENT);
        if (result
                instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors)) {
            fail(errors.format());
        }
        ContentSnapshot snap = result.orThrow();
        PassDef pass = snap.get(PassDef.TYPE, HarborId.parse("pass/s1")).orElseThrow();
        // '6. BM 기획' 3.1: 무료 진주 합계 150, 프리미엄 합계 1,000
        assertEquals(150, PassMath.pearlTotal(pass, PassDef.Track.FREE));
        assertEquals(1000, PassMath.pearlTotal(pass, PassDef.Track.PREMIUM));
        assertEquals(50, pass.levels());
        assertEquals(990, pass.prices().premium());
        assertEquals(1990, pass.prices().premiumPlus());
        assertEquals(15, pass.exp("catch.rare"));
        ChestDef chest = snap.get(ChestDef.TYPE, HarborId.parse("chest/s2_tide")).orElseThrow();
        assertEquals(
                ChestDef.PPM_TOTAL, chest.entries().stream().mapToInt(ChestDef.Entry::ppm).sum());
        ShopSettings settings = snap.single(ShopSettings.TYPE);
        assertFalse(settings.features().chest(), "보물함은 시즌 2 이후 기능이라 꺼져 있어야 한다");
        assertEquals(3, settings.donorGraceDays());
        assertEquals(
                300,
                settings.perk(wiki.creeper.harbor.common.donor.DonorTier.HARBOR).monthlyPearls());
        assertTrue(
                snap.single(RotationPlan.TYPE)
                        .itemsFor(1)
                        .contains(HarborId.parse("cosmetic/rod_creeper")));
    }

    @Test
    void chestRatesMustSumToExactlyHundredPercent() {
        ValidationErrors errors = new ValidationErrors();
        ChestDef.parse(
                node(
                        """
                        id: chest/bad
                        name: 나쁜 상자
                        price: {single: 150, ten: 1350}
                        pity: {count: 50, grade: legendary}
                        entries:
                          - {cosmetic: cosmetic/a, grade: legendary, rate: "0.33333"}
                          - {cosmetic: cosmetic/b, grade: common, rate: "99.6666"}
                        """,
                        errors),
                new ParseContext());
        assertTrue(errors.format().contains("소수점 4자리"), errors.format());
        assertTrue(errors.format().contains("100%"), errors.format());
    }

    @Test
    void cosmeticsCannotBeTradeable() {
        ValidationErrors errors = new ValidationErrors();
        CosmeticDef.parse(
                node(
                        "{id: cosmetic/x, name: X, category: pet, grade: rare, price: 100,"
                                + " availability: permanent, tradeable: true}",
                        errors),
                new ParseContext());
        assertTrue(errors.format().contains("거래"), errors.format());
        assertTrue(errors.format().contains("rig"), errors.format());
    }

    @Test
    void pearlProductsHaveNoBonus() {
        ValidationErrors errors = new ValidationErrors();
        ProductDef.parse(
                node(
                        "{id: product/bonus, tebex_package_id: 1, type: pearls, pearls: 1100, price_krw: 10000}",
                        errors),
                new ParseContext());
        assertTrue(errors.format().contains("보너스"), errors.format());
    }

    @Test
    void rotationCyclesUndefinedWeeks() {
        ValidationErrors errors = new ValidationErrors();
        RotationPlan plan =
                RotationPlan.parse(
                        node(
                                """
                                repeat: true
                                weeks:
                                  - {week: 1, items: [cosmetic/a]}
                                  - {week: 2, items: [cosmetic/b]}
                                  - {week: 3, items: [cosmetic/c]}
                                """,
                                errors),
                        new ParseContext());
        assertTrue(errors.isEmpty(), errors.format());
        assertEquals(List.of(HarborId.parse("cosmetic/b")), plan.itemsFor(2));
        assertEquals(List.of(HarborId.parse("cosmetic/a")), plan.itemsFor(4));
        assertEquals(List.of(HarborId.parse("cosmetic/c")), plan.itemsFor(12));
        assertEquals(List.of(), plan.itemsFor(0));
        RotationPlan once = new RotationPlan(plan.weeks(), false);
        assertEquals(List.of(), once.itemsFor(5));
    }
}
