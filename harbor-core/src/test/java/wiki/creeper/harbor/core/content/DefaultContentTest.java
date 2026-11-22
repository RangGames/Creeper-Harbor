package wiki.creeper.harbor.core.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultContentTest {

    /** Fake default-content jar: path → contents. */
    private static DefaultContent.Resources jar(Map<String, String> files) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put(DefaultContent.INDEX, String.join("\n", files.keySet()) + "\n");
        files.forEach((k, v) -> all.put(DefaultContent.PREFIX + k, v));
        return path -> {
            String text = all.get(path);
            return text == null
                    ? null
                    : (InputStream) new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void createsAllFilesInEmptyFolder(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("content");
        var result =
                DefaultContent.sync(
                        jar(Map.of("balance.yml", "a: 1\n", "fish/coast.yml", "b: 2\n")), target);

        assertEquals(2, result.created());
        assertEquals("a: 1\n", read(target.resolve("balance.yml")));
        assertEquals("b: 2\n", read(target.resolve("fish/coast.yml")));
        assertTrue(Files.exists(target.resolve(DefaultContent.MANIFEST)));
        assertNull(result.backup());
    }

    @Test
    void updatesUntouchedFilesAndKeepsEditedOnes(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("content");
        DefaultContent.sync(jar(Map.of("a.yml", "v: 1\n", "b.yml", "v: 1\n")), target);
        Files.writeString(target.resolve("b.yml"), "edited: true\n");

        var result = DefaultContent.sync(jar(Map.of("a.yml", "v: 2\n", "b.yml", "v: 2\n")), target);

        assertEquals(1, result.updated());
        assertEquals("v: 2\n", read(target.resolve("a.yml")));
        assertEquals("edited: true\n", read(target.resolve("b.yml")));
        assertEquals(List.of("b.yml"), result.keptModified());
    }

    @Test
    void fillsOnlyNewKeysIntoEditedFiles(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("content");
        DefaultContent.sync(jar(Map.of("m.yml", "a: 1\nb: 1\n")), target);
        // The operator edited a and deleted b
        Files.writeString(target.resolve("m.yml"), "a: 9 # 내 값\n");

        var result =
                DefaultContent.sync(jar(Map.of("m.yml", "a: 1\nb: 1\n# 새 항목\nc: 1\n")), target);

        assertEquals(List.of("m.yml"), result.keptModified());
        assertEquals(Map.of("m.yml", List.of("c")), result.filled());
        assertEquals("a: 9 # 내 값\n# 새 항목\nc: 1\n", read(target.resolve("m.yml")));
        // Running it again leaves it unchanged
        var again = DefaultContent.sync(jar(Map.of("m.yml", "a: 1\nb: 1\n# 새 항목\nc: 1\n")), target);
        assertTrue(again.filled().isEmpty());
    }

    @Test
    void removesUntouchedFilesDroppedFromDefaults(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("content");
        DefaultContent.sync(jar(Map.of("a.yml", "v: 1\n", "old.yml", "x: 1\n")), target);

        var result = DefaultContent.sync(jar(Map.of("a.yml", "v: 1\n")), target);

        assertEquals(1, result.removed());
        assertFalse(Files.exists(target.resolve("old.yml")));
    }

    @Test
    void legacyFolderIsBackedUpAndReplaced(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("content");
        Files.createDirectories(target.resolve("rigs"));
        Files.writeString(target.resolve("rigs/fish.yml"), "old placeholder\n");

        var result = DefaultContent.sync(jar(Map.of("rigs/fish.yml", "new\n")), target);

        assertEquals(1, result.updated());
        assertEquals("new\n", read(target.resolve("rigs/fish.yml")));
        Path backup = result.backup();
        assertNotNull(backup);
        assertEquals("old placeholder\n", read(backup.resolve("rigs/fish.yml")));
    }

    @Test
    void rejectsPathsOutsideTarget(@TempDir Path dir) {
        assertThrows(
                IOException.class,
                () -> DefaultContent.sync(jar(Map.of("../escape.yml", "x\n")), dir.resolve("c")));
    }

    @Test
    void failsWhenIndexMissing(@TempDir Path dir) {
        assertThrows(IOException.class, () -> DefaultContent.sync(path -> null, dir));
    }
}
