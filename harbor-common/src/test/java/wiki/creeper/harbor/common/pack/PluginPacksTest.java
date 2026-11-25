package wiki.creeper.harbor.common.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginPacksTest {

    private static Path write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void detectsOnlyInstalledPluginsWithOutput(@TempDir Path plugins) throws IOException {
        write(plugins, "ModelEngine/resource pack/assets/modelengine/items/a.json", "{}");
        write(plugins, "BetterModel/build/assets/bettermodel/items/b.json", "{}");
        write(plugins, "Oraxen/config.yml", "x: 1");

        List<PluginPacks.Found> found =
                PluginPacks.detect(plugins, Set.of("ModelEngine", "Oraxen")::contains);

        assertEquals(1, found.size());
        assertEquals("ModelEngine", found.getFirst().plugin());
        assertEquals(plugins.resolve("ModelEngine/resource pack"), found.getFirst().path());
    }

    @Test
    void picksTheNewerOfFolderAndZip(@TempDir Path plugins) throws IOException {
        Path json =
                write(plugins, "ModelEngine/resource pack/assets/modelengine/items/a.json", "{}");
        Path zip = write(plugins, "ModelEngine/resource pack.zip", "zip");
        Files.setLastModifiedTime(json, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(zip, FileTime.fromMillis(2_000_000L));

        List<PluginPacks.Found> found = PluginPacks.detect(plugins, "ModelEngine"::equals);
        assertEquals(zip, found.getFirst().path());

        Files.setLastModifiedTime(json, FileTime.fromMillis(3_000_000L));
        found = PluginPacks.detect(plugins, "ModelEngine"::equals);
        assertEquals(plugins.resolve("ModelEngine/resource pack"), found.getFirst().path());
    }

    @Test
    void folderWithoutAssetsIsNotAPack(@TempDir Path plugins) throws IOException {
        write(plugins, "ModelEngine/resource pack/README.txt", "empty");
        assertTrue(PluginPacks.detect(plugins, "ModelEngine"::equals).isEmpty());
    }

    @Test
    void fingerprintFollowsChanges(@TempDir Path dir) throws IOException {
        Path harbor = dir.resolve("harbor");
        Path meg = dir.resolve("meg");
        write(harbor, "assets/harbor/items/a.json", "{}");
        Path model = write(meg, "assets/modelengine/models/x.json", "{}");
        List<Path> roots = List.of(harbor, meg);

        long first = PackWatch.fingerprint(roots);
        assertEquals(first, PackWatch.fingerprint(roots));

        Files.writeString(model, "{\"changed\":true}", StandardCharsets.UTF_8);
        long resized = PackWatch.fingerprint(roots);
        assertNotEquals(first, resized);

        write(meg, "assets/modelengine/models/y.json", "{}");
        long added = PackWatch.fingerprint(roots);
        assertNotEquals(resized, added);

        Files.setLastModifiedTime(model, FileTime.fromMillis(5_000_000L));
        assertNotEquals(added, PackWatch.fingerprint(roots));

        // A root disappearing (plugin deleting its folder before regenerating) also counts
        assertNotEquals(
                PackWatch.fingerprint(roots),
                PackWatch.fingerprint(List.of(harbor, dir.resolve("x"))));
    }
}
