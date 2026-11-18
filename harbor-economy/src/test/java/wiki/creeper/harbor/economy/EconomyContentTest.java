package wiki.creeper.harbor.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.economy.content.AuctionRefs;
import wiki.creeper.harbor.economy.content.EconomyContent;
import wiki.creeper.harbor.economy.content.RecipeDef;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.economy.content.StationDef;
import wiki.creeper.harbor.fishing.content.FishingContent;

class EconomyContentTest {

    private static final Set<String> OWNED =
            Set.of(
                    "recipes.yml",
                    "shops.yml",
                    "stations.yml",
                    "auction_refs.yml",
                    "items/crafting.yml");

    private static ContentLoader loader() {
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        FishingContent.TYPES.forEach(loader::register);
        EconomyContent.TYPES.forEach(loader::register);
        loader.addCheck(RecipeDef.TYPE, EconomyContent.RECIPE_CHECK);
        loader.addCheck(ShopDef.TYPE, ShopDef.PRICE_CHECK);
        return loader;
    }

    @Test
    void repositoryContentParses() {
        Path dir = Path.of("..", "content");
        assumeTrue(Files.isDirectory(dir), "content 폴더가 없습니다");
        switch (loader().load(dir)) {
            case Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors) -> {
                List<ValidationError> mine =
                        errors.list().stream()
                                .filter(
                                        e -> {
                                            var pos = e.pos();
                                            String file =
                                                    pos == null
                                                            ? ""
                                                            : pos.file().replace('\\', '/');
                                            return OWNED.stream().anyMatch(file::endsWith);
                                        })
                                .toList();
                assertTrue(mine.isEmpty(), () -> "Economy 콘텐츠 오류: " + mine);
                assumeTrue(false, "다른 콘텐츠 오류로 건너뜀: " + errors.size() + "건");
            }
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) -> {
                RecipeDef legend =
                        snapshot.get(RecipeDef.TYPE, HarborId.parse("recipe/legendary_bait"))
                                .orElseThrow();
                assertEquals(1, legend.weeklyLimit());
                assertEquals("craft:legendary_bait", "craft:" + legend.limitName());
                RecipeDef sashimi =
                        snapshot.get(RecipeDef.TYPE, HarborId.parse("recipe/sashimi_platter"))
                                .orElseThrow();
                assertEquals(RecipeDef.Station.COOKING, sashimi.station());
                assertTrue(
                        sashimi.ingredients().stream()
                                .anyMatch(
                                        i ->
                                                i instanceof RecipeDef.FishMatch m
                                                        && m.gradeMin() == Grade.RARE));
                assertTrue(
                        snapshot.single(AuctionRefs.TYPE)
                                .of(ItemId.parse("item/bait/legendary"))
                                .isPresent());
                assertTrue(
                        snapshot.get(StationDef.TYPE, HarborId.parse("station/auction_board"))
                                .map(s -> s.kind() == StationDef.Kind.AUCTION)
                                .orElse(false));
            }
        }
    }

    @Test
    void badRecipeIsRejectedWithLine(@TempDir Path dir) throws IOException {
        Files.writeString(
                dir.resolve("recipes.yml"),
                """
                - id: recipe/broken
                  result: item/rod/none
                  station: workshop
                  gold: 10
                  ingredients:
                    - {item: item/a, vanilla: minecraft:stone, count: 1}
                    - {vanilla: "Not A Key", count: 1}
                    - {match: {}, count: 1}
                """);
        ContentLoader loader = new ContentLoader();
        loader.register(RecipeDef.TYPE);
        Result<ContentSnapshot, ValidationErrors> result = loader.load(dir);
        assertTrue(result instanceof Result.Err<?, ?>, "잘못된 레시피가 통과했습니다");
        ValidationErrors errors = ((Result.Err<ContentSnapshot, ValidationErrors>) result).error();
        List<Integer> lines =
                errors.list().stream()
                        .map(ValidationError::pos)
                        .filter(java.util.Objects::nonNull)
                        .map(p -> p.line())
                        .toList();
        assertTrue(lines.contains(6), () -> "item+vanilla 오류 줄: " + errors);
        assertTrue(lines.contains(7), () -> "바닐라 키 오류 줄: " + errors);
        assertTrue(lines.contains(8), () -> "빈 match 오류 줄: " + errors);
    }

    @Test
    void validRecipeParses(@TempDir Path dir) throws IOException {
        Files.writeString(
                dir.resolve("recipes.yml"),
                """
                - id: recipe/glow_bait
                  result: item/bait/glow
                  amount: 10
                  station: workshop
                  category: bait
                  gold: 30
                  ingredients:
                    - {item: item/bait/earthworm, count: 10}
                    - {vanilla: minecraft:glow_ink_sac, count: 1}
                    - {match: {grade_min: rare, region: region/harbor}, count: 1}
                  unlock: {job_tier: 2, level: 20, flags: [boss_clear/swamp_catfish]}
                  weekly_limit: 2
                """);
        ContentLoader loader = new ContentLoader();
        loader.register(RecipeDef.TYPE);
        ContentSnapshot snapshot = loader.load(dir).orThrow();
        RecipeDef r =
                snapshot.get(RecipeDef.TYPE, HarborId.parse("recipe/glow_bait")).orElseThrow();
        assertEquals(10, r.amount());
        assertEquals(RecipeDef.Category.BAIT, r.category());
        assertEquals(3, r.ingredients().size());
        assertEquals(2, r.unlock().jobTier());
        assertEquals(List.of("boss_clear/swamp_catfish"), r.unlock().requiredFlags());
        assertEquals(2, r.weeklyLimit());
    }
}
