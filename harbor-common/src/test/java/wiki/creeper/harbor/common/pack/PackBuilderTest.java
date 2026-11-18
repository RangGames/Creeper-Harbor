package wiki.creeper.harbor.common.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackBuilderTest {

    private static final PackBuilder.Meta META = new PackBuilder.Meta("97.1", "테스트");

    private static void write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Path sample(Path dir) throws IOException {
        Path src = dir.resolve("src");
        write(src, "assets/harbor/items/fish/mackerel.json", "{}");
        write(src, "assets/harbor/items/fish/mackerel/head.json", "{}");
        write(src, "assets/harbor/sounds.json", "{\"fishing.cast\":{},\"ui.coin\":{}}");
        write(src, "assets/harbor/rigs/fish/mackerel.json", "{}");
        write(src, "assets/harbor/textures/.DS_Store", "x");
        write(src, "notes.txt", "x");
        return src;
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    @Test
    void buildsDeterministicPackWithoutServerOnlyFiles(@TempDir Path dir) throws IOException {
        Path src = sample(dir);
        PackBuilder.Built a = PackBuilder.build(src, dir.resolve("a.zip"), META);
        PackBuilder.Built b = PackBuilder.build(src, dir.resolve("b.zip"), META);

        assertEquals(a.sha1(), b.sha1());
        assertEquals(40, a.sha1().length());
        List<String> names = entries(a.zip());
        assertEquals(
                List.of(
                        "pack.mcmeta",
                        "assets/harbor/items/fish/mackerel.json",
                        "assets/harbor/items/fish/mackerel/head.json",
                        "assets/harbor/sounds.json"),
                names);
        assertEquals(
                Set.of("harbor:fish/mackerel", "harbor:fish/mackerel/head"), a.index().models());
        assertEquals(Set.of("harbor:fishing.cast", "harbor:ui.coin"), a.index().sounds());
    }

    @Test
    void changedAssetsChangeSha1(@TempDir Path dir) throws IOException {
        Path src = sample(dir);
        String before = PackBuilder.build(src, dir.resolve("a.zip"), META).sha1();
        write(src, "assets/harbor/items/fish/mackerel.json", "{\"model\":{}}");
        assertNotEquals(before, PackBuilder.build(src, dir.resolve("a.zip"), META).sha1());
    }

    @Test
    void keepsGivenMcmeta(@TempDir Path dir) throws IOException {
        Path src = sample(dir);
        write(src, "pack.mcmeta", "{\"pack\":{}}");
        List<String> names = entries(PackBuilder.build(src, dir.resolve("a.zip"), META).zip());
        assertEquals(1, names.stream().filter("pack.mcmeta"::equals).count());
    }

    @Test
    void mcmetaUsesMinMaxFormat() {
        String json = PackBuilder.mcmeta(META);
        assertTrue(json.contains("\"min_format\": [97, 1]"));
        assertTrue(json.contains("\"max_format\": [97, 1]"));
    }

    @Test
    void includeRules() {
        assertTrue(PackBuilder.include("pack.png"));
        assertTrue(PackBuilder.include("assets/minecraft/sounds.json"));
        assertFalse(PackBuilder.include("assets/harbor/rigs/fish/a.json"));
        assertFalse(PackBuilder.include("README.txt"));
        assertFalse(PackBuilder.include("assets/harbor/.git/config"));
    }

    @Test
    void parsesCdnResponse() throws IOException {
        CdnClient.Uploaded up =
                CdnClient.parse(
                        "{\"message\":\"Upload successful\",\"url\":\"https://cdn.example/a/1.0/x.zip\",\"hash\":\"ab\"}");
        assertEquals("https://cdn.example/a/1.0/x.zip", up.url());
        assertEquals("ab", up.hash());
    }

    @Test
    void multipartHasFields() throws IOException {
        String body =
                new String(
                        CdnClient.multipart("B", "creeper-harbor", "1.0", "p.zip", new byte[] {1}),
                        StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"namespace\"\r\n\r\ncreeper-harbor\r\n"));
        assertTrue(body.contains("name=\"version\"\r\n\r\n1.0\r\n"));
        assertTrue(body.contains("filename=\"p.zip\""));
        assertTrue(body.endsWith("\r\n--B--\r\n"));
    }
}
