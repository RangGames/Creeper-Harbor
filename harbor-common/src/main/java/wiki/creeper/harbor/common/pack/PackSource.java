package wiki.creeper.harbor.common.pack;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * One resource pack (folder or zip). Pack path ({@code assets/…}, {@code pack.mcmeta}) → content.
 * If assets sits in a folder one level in, like a downloaded pack, that folder is treated as the
 * root. Files not included in the client pack ({@link PackBuilder#include}) are dropped up front.
 */
final class PackSource implements Closeable {

    private final String name;
    private final Map<String, Path> files = new TreeMap<>();
    private final Map<String, ZipEntry> entries = new TreeMap<>();
    private final @Nullable ZipFile zip;

    private PackSource(String name, @Nullable ZipFile zip) {
        this.name = name;
        this.zip = zip;
    }

    String name() {
        return name;
    }

    Set<String> paths() {
        return zip == null ? files.keySet() : entries.keySet();
    }

    byte[] read(String path) throws IOException {
        ZipFile z = zip;
        if (z == null) {
            Path file = files.get(path);
            if (file == null) {
                throw new IOException(name + "에 " + path + "가 없습니다");
            }
            return Files.readAllBytes(file);
        }
        ZipEntry entry = entries.get(path);
        if (entry == null) {
            throw new IOException(name + "에 " + path + "가 없습니다");
        }
        try (InputStream in = z.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    @Override
    public void close() throws IOException {
        ZipFile z = zip;
        if (z != null) {
            z.close();
        }
    }

    /** Folder or .zip. IOException if there is no assets. */
    static PackSource open(Path path) throws IOException {
        String display = String.valueOf(path.getFileName());
        if (Files.isDirectory(path)) {
            Path root = folderRoot(path);
            PackSource source = new PackSource(display, null);
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (PackBuilder.include(rel)) {
                        source.files.put(rel, file);
                    }
                }
            }
            return source;
        }
        if (Files.isRegularFile(path)
                && display.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            ZipFile zip = new ZipFile(path.toFile());
            try {
                String prefix = zipPrefix(zip);
                PackSource source = new PackSource(display, zip);
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    String name = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || !name.startsWith(prefix)) {
                        continue;
                    }
                    String rel = name.substring(prefix.length());
                    if (PackBuilder.include(rel)) {
                        source.entries.put(rel, entry);
                    }
                }
                return source;
            } catch (IOException | RuntimeException e) {
                zip.close();
                throw e;
            }
        }
        throw new IOException("리소스팩 폴더나 .zip이 아닙니다: " + path);
    }

    private static Path folderRoot(Path dir) throws IOException {
        if (Files.isDirectory(dir.resolve("assets"))) {
            return dir;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                if (Files.isDirectory(child.resolve("assets"))) {
                    return child;
                }
            }
        }
        throw new IOException("assets 폴더가 없는 리소스팩입니다: " + dir.getFileName());
    }

    /** Prefix where assets/ starts inside the zip ("" or "folder/"). */
    private static String zipPrefix(ZipFile zip) throws IOException {
        String found = null;
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            String name = all.nextElement().getName().replace('\\', '/');
            int at = name.indexOf("assets/");
            if (at == 0) {
                return "";
            }
            if (at > 0
                    && name.charAt(at - 1) == '/'
                    && name.lastIndexOf('/', at - 2) < 0
                    && found == null) {
                found = name.substring(0, at);
            }
        }
        if (found == null) {
            throw new IOException("assets 폴더가 없는 리소스팩입니다: " + zip.getName());
        }
        return found;
    }
}
