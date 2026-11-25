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
import java.util.zip.CRC32;
import java.util.zip.Deflater;
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
     * File types Minecraft (and OptiFine) read from a resource pack. Anything else (README.md,
     * .bbmodel, .psd, .txt outside texts/…) is left out of the client pack, as PackSquash does.
     */
    private static final Set<String> PACK_EXTENSIONS =
            Set.of(
                    "png",
                    "json",
                    "mcmeta",
                    "ogg",
                    "fsh",
                    "vsh",
                    "glsl",
                    "ttf",
                    "otf",
                    "lang",
                    "properties",
                    "jem",
                    "jpm");

    /** Entries stored instead of deflated when deflating does not shrink them (proposed). */
    private static final Set<String> PRECOMPRESSED = Set.of("png", "ogg", "zip", "ttf", "otf");

    /** Audio formats Minecraft cannot play (convert to Ogg Vorbis). */
    private static final Set<String> AUDIO = Set.of("mp3", "wav", "flac", "m4a", "aac", "opus");

    /** Number of left-out file names listed in notes (the rest only as a count). */
    private static final int IGNORED_NOTE_LIMIT = 10;

    /**
     * Optimization and file selection for {@link #build}.
     *
     * @param optimize lossless optimizations ({@link PackOptimizer})
     * @param keep extra file extensions (without dot) to include although Minecraft does not read
     *     them
     * @param zip zip conformance level ({@link ZipConformance}, PackSquash style)
     */
    public record Settings(PackOptimizer.Options optimize, Set<String> keep, ZipConformance zip) {
        public static final Settings PLAIN =
                new Settings(PackOptimizer.Options.NONE, Set.of(), ZipConformance.PEDANTIC);

        public Settings {
            keep = Set.copyOf(keep);
        }

        public Settings(PackOptimizer.Options optimize, Set<String> keep) {
            this(optimize, keep, ZipConformance.PEDANTIC);
        }
    }

    /** Where built entries go: a standard zip stream or {@link PackZipWriter}. */
    interface EntrySink extends java.io.Closeable {
        void put(String name, byte[] data, boolean storeIfIncompressible) throws IOException;
    }

    /** Standard zip ({@link ZipConformance#PEDANTIC}). */
    private static final class StandardSink implements EntrySink {
        private final ZipOutputStream zip;

        StandardSink(OutputStream out) {
            this.zip = new ZipOutputStream(out);
            zip.setLevel(9);
        }

        @Override
        public void put(String name, byte[] data, boolean storeIfIncompressible)
                throws IOException {
            PackBuilder.put(zip, name, data, storeIfIncompressible);
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /**
     * @param format pack format to use when there is no pack.mcmeta (e.g. "97.1" → min/max_format
     *     [97, 1])
     * @param description description to use when there is no pack.mcmeta
     */
    public record Meta(String format, String description) {}

    /** Build stage reported to {@link Progress}. */
    public enum Stage {
        /** Optimizing and zipping files (done/total = files). */
        PACK,
        /** Uploading the finished zip (reported by the caller, no file counts). */
        UPLOAD
    }

    /** Build progress, called on the building thread after each file (proposed). */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (stage, done, total) -> {};

        void update(Stage stage, int done, int total);
    }

    /**
     * @param sha1 SHA-1 of the zip (40 hex digits, for client verification)
     * @param files number of files added
     * @param notes notes produced while merging (overlapping files, unreadable packs, left-out
     *     files)
     * @param skipped merge packs that could not be read and were left out
     * @param optimized what the lossless optimizations saved
     * @param ignored number of files left out because Minecraft does not read their type
     */
    public record Built(
            Path zip,
            String sha1,
            long sizeBytes,
            int files,
            PackIndex index,
            List<String> notes,
            List<Path> skipped,
            PackOptimizer.Stats optimized,
            int ignored) {
        public Built {
            notes = List.copyOf(notes);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * item_model and sound lists contained in the pack.
     *
     * @param soundTicks sound event ({@code ns:key}) → length in ticks, for events whose files are
     *     Ogg Vorbis in the pack (longest file of the event)
     */
    public record PackIndex(
            Set<String> models, Set<String> sounds, Map<String, Integer> soundTicks) {
        public PackIndex {
            models = Set.copyOf(models);
            sounds = Set.copyOf(sounds);
            soundTicks = Map.copyOf(soundTicks);
        }

        public PackIndex(Set<String> models, Set<String> sounds) {
            this(models, sounds, Map.of());
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
        return build(source, merges, out, meta, generated, Settings.PLAIN);
    }

    /** As above, with lossless optimizations and file selection ({@link Settings}). */
    public static Built build(
            Path source,
            List<Path> merges,
            Path out,
            Meta meta,
            Map<String, byte[]> generated,
            Settings settings)
            throws IOException {
        return build(source, merges, out, meta, generated, settings, Progress.NONE);
    }

    /** As above, reporting progress after each file. */
    public static Built build(
            Path source,
            List<Path> merges,
            Path out,
            Meta meta,
            Map<String, byte[]> generated,
            Settings settings,
            Progress progress)
            throws IOException {
        if (!hasAssets(source)) {
            throw new IOException("assets 폴더가 없습니다: " + source.getFileName());
        }
        List<String> notes = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        List<PackSource> sources = new ArrayList<>();
        try {
            sources.add(PackSource.open(source, settings.keep()));
            for (Path merge : merges) {
                try {
                    sources.add(PackSource.open(merge, settings.keep()));
                } catch (IOException e) {
                    skipped.add(merge);
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
            int ignored = noteIgnored(sources, notes);
            PackOptimizer optimizer = new PackOptimizer(settings.optimize());
            boolean store = settings.optimize().enabled();
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            boolean hasMeta = owners.containsKey("pack.mcmeta");
            Set<String> models = new TreeSet<>();
            Set<String> sounds = new TreeSet<>();
            Map<String, Integer> fileTicks = new TreeMap<>();
            Map<String, byte[]> soundsJson = new TreeMap<>();
            ZipConformance level = settings.zip();
            PackZipWriter writer =
                    level == ZipConformance.PEDANTIC
                            ? null
                            : new PackZipWriter(Files.newOutputStream(tmp), level);
            try (EntrySink zip =
                    writer != null ? writer : new StandardSink(Files.newOutputStream(tmp))) {
                if (!hasMeta) {
                    zip.put("pack.mcmeta", mcmeta(meta).getBytes(StandardCharsets.UTF_8), false);
                }
                int total = owners.size();
                int done = 0;
                progress.update(Stage.PACK, 0, total);
                for (Map.Entry<String, List<PackSource>> e : owners.entrySet()) {
                    String path = e.getKey();
                    byte[] made = generated.get(path);
                    byte[] data =
                            optimizer.apply(
                                    path,
                                    made != null
                                            ? made
                                            : PackMerge.resolve(path, e.getValue(), notes));
                    zip.put(path, data, store);
                    itemModelKey(path).ifPresent(models::add);
                    soundsNamespace(path)
                            .ifPresent(
                                    ns -> {
                                        PackMerge.soundKeys(ns, data, sounds);
                                        soundsJson.put(ns, data);
                                    });
                    String soundFile = SoundLengths.fileKey(path);
                    if (soundFile != null) {
                        fileTicks.put(soundFile, SoundLengths.oggTicks(data));
                    }
                    progress.update(Stage.PACK, ++done, total);
                }
            }
            if (writer != null) {
                notes.add(
                        "zip: "
                                + level.key()
                                + " (같은 내용 파일 "
                                + writer.shared()
                                + "개 공유"
                                + (level == ZipConformance.DISREGARD
                                        ? ", 압축 해제 방지: 일반 압축 프로그램으로는 잘 안 풀림"
                                        : "")
                                + ")");
            }
            Map<String, Integer> soundTicks = new TreeMap<>();
            soundsJson.forEach((ns, json) -> SoundLengths.events(ns, json, fileTicks, soundTicks));
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            optimizer.pruneCache();
            return new Built(
                    out,
                    sha1(out),
                    Files.size(out),
                    owners.size() + (hasMeta ? 0 : 1),
                    new PackIndex(models, sounds, soundTicks),
                    notes,
                    skipped,
                    optimizer.stats(),
                    ignored);
        } finally {
            for (PackSource s : sources) {
                s.close();
            }
        }
    }

    /** Lists left-out file types in notes (first few names, then a count). */
    private static int noteIgnored(List<PackSource> sources, List<String> notes) {
        List<String> all = new ArrayList<>();
        for (PackSource s : sources) {
            for (String path : s.ignored()) {
                all.add(s.name() + ":" + path);
            }
        }
        List<String> audio =
                all.stream().filter(p -> AUDIO.contains(PackOptimizer.extension(p))).toList();
        if (!audio.isEmpty()) {
            notes.add(
                    "마인크래프트는 소리로 Ogg Vorbis(.ogg)만 재생합니다. 다음 파일은 .ogg로 바꿔 넣어야 합니다: "
                            + String.join(", ", audio));
        }
        if (!all.isEmpty()) {
            String sample =
                    String.join(", ", all.subList(0, Math.min(IGNORED_NOTE_LIMIT, all.size())));
            notes.add(
                    "마인크래프트가 읽지 않는 파일 "
                            + all.size()
                            + "개를 팩에서 뺐습니다 ("
                            + sample
                            + (all.size() > IGNORED_NOTE_LIMIT ? ", …" : "")
                            + "). 꼭 넣으려면 pack.keep에 확장자를 적습니다");
        }
        return all.size();
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

    /**
     * Whether Minecraft reads this file type (pack.mcmeta/pack.png at the root, {@link
     * #PACK_EXTENSIONS} under assets, unifont-style .zip under font/), or its extension is in keep.
     */
    static boolean recognized(String path, Set<String> keep) {
        if (path.equals("pack.mcmeta") || path.equals("pack.png")) {
            return true;
        }
        String ext = PackOptimizer.extension(path);
        if (PACK_EXTENSIONS.contains(ext) || keep.contains(ext)) {
            return true;
        }
        String[] parts = path.split("/", -1);
        if (ext.equals("txt")) {
            // splashes.txt, end.txt, postcredits.txt…
            return parts.length >= 4 && parts[2].equals("texts");
        }
        return ext.equals("zip") && parts.length >= 4 && parts[2].equals("font");
    }

    /**
     * @param storeIfIncompressible store (no deflate) already-compressed files that deflating does
     *     not shrink
     */
    private static void put(
            ZipOutputStream zip, String name, byte[] data, boolean storeIfIncompressible)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME.toMillis());
        if (storeIfIncompressible
                && PRECOMPRESSED.contains(PackOptimizer.extension(name))
                && deflatedSize(data) >= data.length) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        OutputStream out = zip;
        out.write(data);
        zip.closeEntry();
    }

    private static long deflatedSize(byte[] data) {
        Deflater deflater = new Deflater(9, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] buf = new byte[64 * 1024];
            long size = 0;
            while (!deflater.finished()) {
                size += deflater.deflate(buf);
            }
            return size;
        } finally {
            deflater.end();
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String name(Path path) {
        Path file = path.getFileName();
        return file == null ? "" : file.toString().replace("/", "");
    }
}
