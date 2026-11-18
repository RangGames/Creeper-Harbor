package wiki.creeper.harbor.core.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultContentTest {

    private static DefaultContent.Resources jar(Map<String, String> files) {
        return path -> {
            String text = files.get(path);
            return text == null
                    ? null
                    : (InputStream) new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void extractsAllFilesIntoEmptyFolder(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("content");
        var resources =
                jar(
                        Map.of(
                                DefaultContent.INDEX,
                                "balance.yml\nfish/coast.yml\n",
                                "harbor-content/balance.yml",
                                "a: 1\n",
                                "harbor-content/fish/coast.yml",
                                "b: 2\n"));

        assertEquals(2, DefaultContent.extractMissing(resources, target));
        assertEquals("a: 1\n", Files.readString(target.resolve("balance.yml")));
        assertEquals("b: 2\n", Files.readString(target.resolve("fish/coast.yml")));
    }

    @Test
    void keepsExistingFilesAndAddsOnlyMissing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("balance.yml"), "edited: true\n");
        var resources =
                jar(
                        Map.of(
                                DefaultContent.INDEX,
                                "balance.yml\nnew.yml\n",
                                "harbor-content/balance.yml",
                                "a: 1\n",
                                "harbor-content/new.yml",
                                "c: 3\n"));

        assertEquals(1, DefaultContent.extractMissing(resources, dir));
        assertEquals("edited: true\n", Files.readString(dir.resolve("balance.yml")));
        assertEquals("c: 3\n", Files.readString(dir.resolve("new.yml")));
    }

    @Test
    void rejectsPathsOutsideTarget(@TempDir Path dir) {
        var resources = jar(Map.of(DefaultContent.INDEX, "../escape.yml\n"));

        assertThrows(IOException.class, () -> DefaultContent.extractMissing(resources, dir));
    }

    @Test
    void failsWhenIndexMissing(@TempDir Path dir) {
        assertThrows(IOException.class, () -> DefaultContent.extractMissing(jar(Map.of()), dir));
    }
}
