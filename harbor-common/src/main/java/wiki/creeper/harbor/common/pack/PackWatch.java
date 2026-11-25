package wiki.creeper.harbor.common.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

/**
 * Cheap change check for pack sources (file names, sizes and modification times, no content reads),
 * used to rebuild the pack when another plugin regenerates its pack (proposed).
 */
public final class PackWatch {

    private PackWatch() {}

    /**
     * Fingerprint of the given roots (folders or files). Changes when a file is added, removed,
     * resized or rewritten, or a root appears or disappears. Unreadable entries count as missing.
     */
    public static long fingerprint(List<Path> roots) {
        long sum = roots.size();
        for (int i = 0; i < roots.size(); i++) {
            Path root = roots.get(i);
            if (Files.isRegularFile(root)) {
                sum += entry(i, "", root);
            } else if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        String rel = root.relativize(file).toString().replace('\\', '/');
                        sum += entry(i, rel, file);
                    }
                } catch (IOException | UncheckedIOException e) {
                    // Being rewritten: a later check sees the settled state
                    sum += mix(i * 31L + 7);
                }
            } else {
                sum += mix(i * 31L + 3);
            }
        }
        return sum;
    }

    /** Latest modification time under root (epoch ms; Long.MIN_VALUE if unreadable). */
    public static long lastModified(Path root) {
        try {
            if (!Files.isDirectory(root)) {
                return Files.getLastModifiedTime(root).toMillis();
            }
            try (Stream<Path> walk = Files.walk(root)) {
                long latest = Long.MIN_VALUE;
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis());
                }
                return latest;
            }
        } catch (IOException | UncheckedIOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static long entry(int root, String rel, Path file) {
        long h = root;
        h = h * 31 + rel.hashCode();
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            h = h * 31 + attrs.size();
            h = h * 31 + attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            h = h * 31 - 1;
        }
        return mix(h);
    }

    /** SplitMix64 finalizer, so the order-independent sum does not cancel out. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
