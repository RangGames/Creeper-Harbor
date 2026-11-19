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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * 에셋 폴더 → 클라이언트 리소스팩 zip (ItemsAdder처럼 에셋만 넣으면 서버가 압축한다, 추가 제안).
 *
 * <p>폴더 구성: {@code assets/<ns>/...}, 선택 {@code pack.mcmeta}·{@code pack.png}. pack.mcmeta가 없으면 만든다.
 * 서버용 리그 데이터({@code assets/<ns>/rigs/})와 숨김·OS 파일은 넣지 않는다. 항목 순서와 시각을 고정해서 내용이 같으면 SHA-1도 같다 (같은 팩을
 * 다시 올리지 않음).
 */
public final class PackBuilder {

    /** 고정 항목 시각 (2000-01-01 UTC). zip의 DOS 시각 범위 안이어야 한다. */
    private static final FileTime FIXED_TIME = FileTime.fromMillis(946_684_800_000L);

    private static final Set<String> SKIP_NAMES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

    /**
     * @param format pack.mcmeta가 없을 때 쓸 팩 형식 (예: "97.1" → min/max_format [97, 1])
     * @param description pack.mcmeta가 없을 때 쓸 설명
     */
    public record Meta(String format, String description) {}

    /**
     * @param sha1 zip의 SHA-1 (16진수 40자리, 클라이언트 검증용)
     * @param files 넣은 파일 수
     */
    public record Built(Path zip, String sha1, long sizeBytes, int files, PackIndex index) {}

    /** 팩에 든 item_model·소리 목록. */
    public record PackIndex(Set<String> models, Set<String> sounds) {
        public PackIndex {
            models = Set.copyOf(models);
            sounds = Set.copyOf(sounds);
        }
    }

    private PackBuilder() {}

    /** 에셋 폴더에 assets가 있는지. */
    public static boolean hasAssets(Path source) {
        return Files.isDirectory(source.resolve("assets"));
    }

    /** source를 압축해 out에 쓴다 (임시 파일에 쓴 뒤 바꿔 넣는다). */
    public static Built build(Path source, Path out, Meta meta) throws IOException {
        return build(source, out, meta, Map.of());
    }

    /**
     * source에 generated(팩 경로 → 내용, 서버가 만든 파일)를 더해 압축한다. 같은 경로면 generated가 이긴다.
     *
     * @param generated 예: 커스텀 블록의 note_block.json·모델 ({@link FurnishingPack})
     */
    public static Built build(Path source, Path out, Meta meta, Map<String, byte[]> generated)
            throws IOException {
        if (!hasAssets(source)) {
            throw new IOException("assets 폴더가 없습니다: " + source.getFileName());
        }
        Map<String, @Nullable Path> entries = new TreeMap<>();
        for (Path file : files(source)) {
            entries.put(relative(source, file), file);
        }
        generated.keySet().forEach(name -> entries.put(name, null));
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        boolean hasMeta = entries.containsKey("pack.mcmeta");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            zip.setLevel(9);
            if (!hasMeta) {
                put(zip, "pack.mcmeta", mcmeta(meta).getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, @Nullable Path> e : entries.entrySet()) {
                byte[] made = generated.get(e.getKey());
                put(
                        zip,
                        e.getKey(),
                        made != null
                                ? made
                                : Files.readAllBytes(Objects.requireNonNull(e.getValue())));
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        PackIndex scanned = scan(source);
        Set<String> models = new TreeSet<>(scanned.models());
        for (String name : generated.keySet()) {
            itemModelKey(name).ifPresent(models::add);
        }
        return new Built(
                out,
                sha1(out),
                Files.size(out),
                entries.size() + (hasMeta ? 0 : 1),
                new PackIndex(models, scanned.sounds()));
    }

    /** {@code assets/<ns>/items/<경로>.json} → {@code <ns>:<경로>}. */
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

    /** 에셋 폴더(또는 압축을 푼 팩)의 item_model·소리 목록. */
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

    private static List<Path> files(Path source) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> include(relative(source, f)))
                    .sorted((a, b) -> relative(source, a).compareTo(relative(source, b)))
                    .toList();
        }
    }

    /** 클라이언트 팩에 넣을 경로인지 (pack.mcmeta·pack.png·assets 아래, 리그·숨김 파일 제외). */
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
