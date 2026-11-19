package wiki.creeper.harbor.common.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.id.HarborId;

class FurnishingPackTest {

    private static CustomBlockDef tile(String name, int state, String texture) {
        return new CustomBlockDef(
                HarborId.parse("block/" + name),
                name,
                state,
                null,
                texture,
                null,
                null,
                null,
                Map.of(),
                CustomBlockDef.Rotation.NONE,
                CustomBlockDef.Breaking.NOTE_BLOCK,
                List.of(),
                false,
                0,
                0,
                null,
                null);
    }

    private static String text(Map<String, byte[]> files, String path) {
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void blockstateHasEveryVariant() {
        CustomBlockDef marble = tile("marble", 26, "harbor:block/marble");
        FurnishingPack.Result result = FurnishingPack.generate(List.of(marble), null);
        JsonObject variants =
                JsonParser.parseString(text(result.files(), FurnishingPack.BLOCKSTATE))
                        .getAsJsonObject()
                        .getAsJsonObject("variants");
        assertEquals(800, variants.size());
        String custom = "harbor:generated/block/marble";
        assertEquals(
                custom,
                variants.getAsJsonObject("instrument=basedrum,note=1,powered=false")
                        .get("model")
                        .getAsString());
        assertEquals(
                custom,
                variants.getAsJsonObject("instrument=basedrum,note=1,powered=true")
                        .get("model")
                        .getAsString());
        assertEquals(
                FurnishingPack.VANILLA,
                variants.getAsJsonObject("instrument=harp,note=0,powered=false")
                        .get("model")
                        .getAsString());
        assertTrue(
                text(result.files(), "assets/harbor/models/generated/block/marble.json")
                        .contains("\"all\":\"harbor:block/marble\""));
        assertTrue(
                text(result.files(), "assets/harbor/items/generated/block/marble.json")
                        .contains(custom));
    }

    @Test
    void skipsBlocksWithMissingTextures() {
        FurnishingPack.Result result =
                FurnishingPack.generate(
                        List.of(
                                tile("a", 1, "harbor:block/a"),
                                tile("b", 2, "minecraft:block/stone")),
                        path -> false);
        assertEquals(1, result.warnings().size());
        assertFalse(result.files().containsKey("assets/harbor/items/generated/block/a.json"));
        assertTrue(result.files().containsKey("assets/harbor/items/generated/block/b.json"));
    }

    @Test
    void noBlocksNoFiles() {
        assertTrue(FurnishingPack.generate(List.of(), null).files().isEmpty());
    }

    @Test
    void generatedFilesJoinPackAndIndex(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src");
        Path tex = src.resolve("assets/harbor/textures/block/marble.png");
        Files.createDirectories(tex.getParent());
        Files.write(tex, new byte[] {1, 2, 3});
        FurnishingPack.Result result =
                FurnishingPack.generate(
                        List.of(tile("marble", 1, "harbor:block/marble")),
                        p -> Files.isRegularFile(src.resolve(p)));
        PackBuilder.Built built =
                PackBuilder.build(
                        src,
                        dir.resolve("a.zip"),
                        new PackBuilder.Meta("97.1", "t"),
                        result.files());
        assertTrue(built.index().models().contains("harbor:generated/block/marble"));
        try (ZipFile zip = new ZipFile(built.zip().toFile())) {
            assertTrue(zip.getEntry(FurnishingPack.BLOCKSTATE) != null);
            assertTrue(zip.getEntry("assets/harbor/textures/block/marble.png") != null);
        }
        String again =
                PackBuilder.build(
                                src,
                                dir.resolve("b.zip"),
                                new PackBuilder.Meta("97.1", "t"),
                                result.files())
                        .sha1();
        assertEquals(built.sha1(), again);
    }

    @Test
    void axisBlocksRotateLikeLogs() {
        CustomBlockDef log =
                new CustomBlockDef(
                        HarborId.parse("block/driftwood"),
                        "유목",
                        50,
                        "harbor:block/driftwood",
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        CustomBlockDef.Rotation.AXIS,
                        CustomBlockDef.Breaking.NOTE_BLOCK,
                        List.of(),
                        false,
                        0,
                        0,
                        null,
                        null);
        JsonObject variants =
                JsonParser.parseString(
                                text(
                                        FurnishingPack.generate(List.of(log), null).files(),
                                        FurnishingPack.BLOCKSTATE))
                        .getAsJsonObject()
                        .getAsJsonObject("variants");
        JsonObject y = variants.getAsJsonObject(CustomBlockDef.properties(50, false));
        JsonObject x = variants.getAsJsonObject(CustomBlockDef.properties(51, false));
        JsonObject z = variants.getAsJsonObject(CustomBlockDef.properties(52, true));
        assertFalse(y.has("x"));
        assertEquals(90, x.get("x").getAsInt());
        assertEquals(90, x.get("y").getAsInt());
        assertEquals(90, z.get("x").getAsInt());
        assertFalse(z.has("y"));
        assertEquals("harbor:block/driftwood", z.get("model").getAsString());
    }
}
