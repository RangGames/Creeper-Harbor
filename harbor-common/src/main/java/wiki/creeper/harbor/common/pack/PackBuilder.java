package wiki.creeper.harbor.common.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Asset folder → client resource pack zip (like ItemsAdder: drop in the assets and the server zips
 * them, proposed).
 *
 * <p>Folder layout: {@code assets/<ns>/...}, optional {@code pack.mcmeta} and {@code pack.png}.
 * pack.mcmeta is generated if missing. Server-side rig data ({@code assets/<ns>/rigs/}) and
 * hidden/OS files are left out. Entry order and timestamps are fixed, so the same content gives the
 * same SHA-1 (the same pack is not uploaded again).
 */
public final class PackBuilder {

    /** Fixed entry timestamp (2000-01-01 UTC). Must be within the zip DOS time range. */
    private static final FileTime FIXED_TIME = FileTime.fromMillis(946_684_800_000L);

    private static final Set<String> SKIP_NAMES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

    /**
     * @param format pack format to use when there is no pack.mcmeta (e.g. "97.1" → min/max_format
     *     [97, 1])
     * @param description description to use when there is no pack.mcmeta
     */
    public record Meta(String format, String description) {}

    /**
     * @param sha1 SHA-1 of the zip (40 hex digits, for client verification)
     * @param files number of files added
     * @param notes notes produced while merging (overlapping files, unreadable packs)
     */
    public record Built(
            Path zip, String sha1, long sizeBytes, int files, PackIndex index, List<String> notes) {
        public Built {
            notes = List.copyOf(notes);
        }
    }

    /** item_model and sound lists contained in the pack. */
    public record PackIndex(Set<String> models, Set<String> sounds) {
        public PackIndex {
            models = Set.copyOf(models);
            sounds = Set.copyOf(sounds);
        }
    }

    private PackBuilder() {}

    /** Whether the asset folder contains assets. */
    public static boolean hasAssets(Path source) {
        return Files.isDirectory(source.resolve("assets"));
    }

    /** Zips source into out (writes to a temp file, then swaps it in). */
    public static Built build(Path source, Path out, Meta meta) throws IOException {
        return build(source, List.of(), out, meta, Map.of());
    }

    /** source + generated (no packs to merge). */
    public static Built build(Path source, Path out, Meta meta, Map<String, byte[]> generated)
            throws IOException {
        return build(source, List.of(), out, meta, generated);
    }

    /**
     * Merges source (the Harbor pack) with other resource packs (merges, folders or zips) and
     * generated (server-generated files) and zips them (same purpose as ItemsAdder's pack merging,
     * proposed).
     *
     * <ul>
     *   <li>For the same path, generated → source → earlier merges win, in that order. pack.mcmeta
     *       and pack.png are taken from source only
     *   <li>Mergeable JSON is merged: sounds.json and lang/*.json per key (earlier wins),
     *       atlases/*.json as the union of sources, font/*.json by appending providers from the
     *       earliest
     *   <li>Other overlapping files use the earlier one and are noted in notes. Unreadable packs
     *       are skipped and noted in notes
     * </ul>
     */
    public static Built build(
            Path source, List<Path> merges, Path out, Meta meta, Map<String, byte[]> generated)
            throws IOException {
        if (!hasAssets(source)) {
            throw new IOException("assets 폴더가 없습니다: " + source.getFileName());
        }
        List<String> notes = new ArrayList<>();
        List<PackSource> sources = new ArrayList<>();
        try {
            sources.add(PackSource.open(source));
            for (Path merge : merges) {
                try {
                    sources.add(PackSource.open(merge));
                } catch (IOException e) {
                    notes.add("합칠 팩을 읽지 못했습니다: " + merge + " (" + e.getMessage() + ")");
                }
            }
            Map<String, List<PackSource>> owners = new TreeMap<>();
            for (int i = 0; i < sources.size(); i++) {
                PackSource s = sources.get(i);
                for (String path : s.paths()) {
                    if (i > 0 && (path.equals("pack.mcmeta") || path.equals("pack.png"))) {
                        continue;
                    }
                    owners.computeIfAbsent(path, k -> new ArrayList<>()).add(s);
                }
            }
            generated.keySet().forEach(name -> owners.putIfAbsent(name, List.of()));
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            boolean hasMeta = owners.containsKey("pack.mcmeta");
            Set<String> models = new TreeSet<>();
            Set<String> sounds = new TreeSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                zip.setLevel(9);
                if (!hasMeta) {
                    put(zip, "pack.mcmeta", mcmeta(meta).getBytes(StandardCharsets.UTF_8));
                }
                for (Map.Entry<String, List<PackSource>> e : owners.entrySet()) {
                    String path = e.getKey();
                    byte[] made = generated.get(path);
                    byte[] data =
                            made != null ? made : PackMerge.resolve(path, e.getValue(), notes);
                    put(zip, path, data);
                    itemModelKey(path).ifPresent(models::add);
                    soundsNamespace(path).ifPresent(ns -> PackMerge.soundKeys(ns, data, sounds));
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return new Built(
                    out,
                    sha1(out),
                    Files.size(out),
                    owners.size() + (hasMeta ? 0 : 1),
                    new PackIndex(models, sounds),
                    notes);
        } finally {
            for (PackSource s : sources) {
                s.close();
            }
        }
    }

    /** {@code assets/<ns>/sounds.json} → ns. */
    private static Optional<String> soundsNamespace(String path) {
        String[] parts = path.split("/", -1);
        return parts.length == 3 && parts[0].equals("assets") && parts[2].equals("sounds.json")
                ? Optional.of(parts[1])
                : Optional.empty();
    }

    /** {@code assets/<ns>/items/<path>.json} → {@code <ns>:<path>}. */
    static Optional<String> itemModelKey(String path) {
        String[] parts = path.split("/", 4);
        if (parts.length < 4
                || !parts[0].equals("assets")
                || !parts[2].equals("items")
                || !parts[3].endsWith(".json")) {
            return Optional.empty();
        }
        return Optional.of(
                parts[1] + ":" + parts[3].substring(0, parts[3].length() - ".json".length()));
    }

    /** item_model and sound lists of the asset folder (or an extracted pack). */
    public static PackIndex scan(Path root) throws IOException {
        Set<String> models = new TreeSet<>();
        Set<String> sounds = new TreeSet<>();
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return new PackIndex(models, sounds);
        }
        try (Stream<Path> namespaces = Files.list(assets)) {
            for (Path ns : namespaces.filter(Files::isDirectory).sorted().toList()) {
                String namespace = name(ns);
                Path items = ns.resolve("items");
                if (Files.isDirectory(items)) {
                    try (Stream<Path> walk = Files.walk(items)) {
                        walk.filter(f -> Files.isRegularFile(f) && name(f).endsWith(".json"))
                                .forEach(
                                        f -> {
                                            String key = relative(items, f);
                                            models.add(
                                                    namespace
                                                            + ":"
                                                            + key.substring(
                                                                    0,
                                                                    key.length()
                                                                            - ".json".length()));
                                        });
                    }
                }
                Path soundsJson = ns.resolve("sounds.json");
                if (Files.isRegularFile(soundsJson)) {
                    try (Reader reader =
                            Files.newBufferedReader(soundsJson, StandardCharsets.UTF_8)) {
                        JsonElement json = JsonParser.parseReader(reader);
                        if (json.isJsonObject()) {
                            for (String key : json.getAsJsonObject().keySet()) {
                                sounds.add(namespace + ":" + key);
                            }
                        }
                    }
                }
            }
        }
        return new PackIndex(models, sounds);
    }

    /** "97.1" → {"pack":{"description":…,"min_format":[97,1],"max_format":[97,1]}}. */
    static String mcmeta(Meta meta) {
        String[] parts = meta.format().trim().split("\\.", -1);
        String array =
                parts.length >= 2
                        ? "[" + Integer.parseInt(parts[0]) + ", " + Integer.parseInt(parts[1]) + "]"
                        : String.valueOf(Integer.parseInt(parts[0]));
        String description = meta.description().replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\n  \"pack\": {\n    \"description\": \""
                + description
                + "\",\n    \"min_format\": "
                + array
                + ",\n    \"max_format\": "
                + array
                + "\n  }\n}\n";
    }

    static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether a path goes into the client pack (pack.mcmeta, pack.png, under assets; excluding rigs
     * and hidden files).
     */
    static boolean include(String path) {
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.startsWith(".") || SKIP_NAMES.contains(part)) {
                return false;
            }
        }
        if (path.equals("pack.mcmeta") || path.equals("pack.png")) {
            return true;
        }
        if (parts.length < 3 || !parts[0].equals("assets")) {
            return false;
        }
        return !parts[2].equals("rigs");
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME.toMillis());
        zip.putNextEntry(entry);
        OutputStream out = zip;
        out.write(data);
        zip.closeEntry();
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String name(Path path) {
        Path file = path.getFileName();
        return file == null ? "" : file.toString().replace("/", "");
    }
}
