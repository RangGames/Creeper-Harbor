package wiki.creeper.harbor.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.Messages;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;

class ContentLoaderTest {

    @TempDir Path root;
    ContentLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        loader = new ContentLoader();
        CoreContent.register(loader);
        write(
                "regions.yml",
                """
                - id: region/harbor
                  name: 크리퍼 항구
                  world: harbor_main
                  bounds: [[0, 0], [100, 0], [100, 100], [0, 100]]
                  biome: creeper:harbor
                """);
        write(
                "items/bait.yml",
                """
                - id: item/bait/earthworm
                  name: 지렁이
                  type: bait
                  grade: common
                  base_item: minecraft:wheat_seeds
                  model: harbor:bait/earthworm
                  tradeable: true
                  season_reset: false
                """);
        write(
                "balance.yml",
                """
                fishing:
                  legend:
                    pity_start: 150
                """);
        write(
                "messages_ko.yml",
                """
                harbor:
                  reload:
                    ok: "<green>리로드 완료"
                """);
    }

    @Test
    void loadsValidContent() throws IOException {
        write("fish/harbor.yml", fish("fish/harbor_mackerel", 1, "[20, 45]", "region/harbor"));

        ContentSnapshot snapshot = loader.load(root).orThrow();

        FishDef mackerel =
                snapshot.get(FishDef.TYPE, HarborId.parse("fish/harbor_mackerel")).orElseThrow();
        assertEquals(Grade.COMMON, mackerel.grade());
        assertEquals(20, mackerel.sizeMin());
        assertEquals(150, snapshot.single(Balance.TYPE).integer("fishing.legend.pity_start", 0));
        assertEquals(
                "<green>리로드 완료",
                snapshot.single(Messages.TYPE).get("harbor.reload.ok").orElseThrow());
    }

    @Test
    void reportsAllErrorsWithLineNumbers() throws IOException {
        write("fish/harbor.yml", fish("fish/harbor_mackerel", 1, "[45, 20]", "region/nowhere"));

        Result<ContentSnapshot, ValidationErrors> result = loader.load(root);

        assertFalse(result.isOk());
        String errors = ((Result.Err<ContentSnapshot, ValidationErrors>) result).error().format();
        assertTrue(errors.contains("fish/harbor.yml 7행"), errors);
        assertTrue(errors.contains("size_cm 최솟값이 최댓값보다 큼"), errors);
    }

    @Test
    void reportsMissingReference() throws IOException {
        write("fish/harbor.yml", fish("fish/harbor_mackerel", 1, "[20, 45]", "region/nowhere"));

        String errors =
                ((Result.Err<ContentSnapshot, ValidationErrors>) loader.load(root))
                        .error()
                        .format();

        assertTrue(errors.contains("region 'harbor:region/nowhere'를 찾을 수 없습니다"), errors);
    }

    @Test
    void reportsDuplicateCodexNumber() throws IOException {
        write(
                "fish/harbor.yml",
                fish("fish/a", 1, "[20, 45]", "region/harbor")
                        + fish("fish/b", 1, "[20, 45]", "region/harbor"));

        String errors =
                ((Result.Err<ContentSnapshot, ValidationErrors>) loader.load(root))
                        .error()
                        .format();

        assertTrue(errors.contains("도감 번호 1"), errors);
    }

    @Test
    void partialReloadKeepsOtherTypes() throws IOException {
        write("fish/harbor.yml", fish("fish/harbor_mackerel", 1, "[20, 45]", "region/harbor"));
        ContentSnapshot first = loader.load(root).orThrow();
        write("balance.yml", "fishing:\n  legend:\n    pity_start: 120\n");
        write("fish/harbor.yml", "this is: [broken");

        ContentSnapshot second = loader.reload(root, Set.of("balance"), first).orThrow();

        assertEquals(120, second.single(Balance.TYPE).integer("fishing.legend.pity_start", 0));
        assertEquals(1, second.all(FishDef.TYPE).size());
    }

    private static String fish(String id, int no, String size, String region) {
        return """
                - id: %s
                  no: %d
                  name: 테스트 어종
                  lore: 설명
                  grade: common
                  region: %s
                  size_cm: %s
                  base_price: 10
                  bait: [item/bait/earthworm]
                  model: harbor:fish/test
                  season: 1
                """
                .formatted(id, no, region, size);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
