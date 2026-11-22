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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackMergeTest {

    private static final PackBuilder.Meta META = new PackBuilder.Meta("97.1", "t");

    private static void write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String entry(Path zip, String name) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(name);
            return e == null
                    ? ""
                    : new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void mergesFoldersAndNestedZips(@TempDir Path dir) throws IOException {
        Path harbor = dir.resolve("harbor");
        write(harbor, "pack.mcmeta", "{\"pack\":{\"description\":\"harbor\"}}");
        write(harbor, "assets/harbor/sounds.json", "{\"ui.coin\":{\"a\":1}}");
        write(harbor, "assets/minecraft/sounds.json", "{\"x\":{\"from\":\"harbor\"}}");
        write(
                harbor,
                "assets/minecraft/atlases/blocks.json",
                "{\"sources\":[{\"type\":\"directory\",\"source\":\"harbor\"}]}");
        write(harbor, "assets/minecraft/textures/item/paper.png", "HARBOR");

        // Another plugin's folder (assets one level in)
        Path other = dir.resolve("other");
        write(other, "inner/pack.mcmeta", "{\"pack\":{\"description\":\"other\"}}");
        write(other, "inner/assets/minecraft/sounds.json", "{\"x\":{\"from\":\"other\"},\"y\":{}}");
        write(
                other,
                "inner/assets/minecraft/atlases/blocks.json",
                "{\"sources\":[{\"type\":\"directory\",\"source\":\"meg\"},{\"type\":\"directory\",\"source\":\"harbor\"}]}");
        write(other, "inner/assets/minecraft/textures/item/paper.png", "OTHER");
        write(other, "inner/assets/meg/models/a.json", "{}");

        // zip (inner folder Pack/)
        Path zip = dir.resolve("extra.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("Pack/assets/extra/items/thing.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("Pack/assets/minecraft/sounds.json"));
            out.write("{\"z\":{}}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Path built = dir.resolve("out.zip");
        PackBuilder.Built result =
                PackBuilder.build(harbor, List.of(other, zip), built, META, Map.of());

        // pack.mcmeta is Harbor's
        assertTrue(entry(built, "pack.mcmeta").contains("harbor"));
        // sounds.json keys merged (Harbor wins)
        JsonObject sounds =
                JsonParser.parseString(entry(built, "assets/minecraft/sounds.json"))
                        .getAsJsonObject();
        assertEquals("harbor", sounds.getAsJsonObject("x").get("from").getAsString());
        assertTrue(sounds.has("y") && sounds.has("z"));
        // atlas union (duplicates once)
        JsonObject atlas =
                JsonParser.parseString(entry(built, "assets/minecraft/atlases/blocks.json"))
                        .getAsJsonObject();
        assertEquals(2, atlas.getAsJsonArray("sources").size());
        // Overlapping file is Harbor's + a note
        assertEquals("HARBOR", entry(built, "assets/minecraft/textures/item/paper.png"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("paper.png")));
        // Files, models and sounds from the other packs are included too
        assertFalse(entry(built, "assets/meg/models/a.json").isEmpty());
        assertTrue(result.index().models().contains("extra:thing"));
        assertTrue(
                result.index()
                        .sounds()
                        .containsAll(List.of("minecraft:x", "minecraft:z", "harbor:ui.coin")));
    }

    @Test
    void skipsUnreadablePacks(@TempDir Path dir) throws IOException {
        Path harbor = dir.resolve("harbor");
        write(harbor, "assets/harbor/items/a.json", "{}");
        PackBuilder.Built result =
                PackBuilder.build(
                        harbor,
                        List.of(dir.resolve("missing")),
                        dir.resolve("o.zip"),
                        META,
                        Map.of());
        assertEquals(1, result.notes().size());
        assertTrue(result.index().models().contains("harbor:a"));
    }
}
