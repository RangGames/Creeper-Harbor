package wiki.creeper.harbor.core.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.yaml.YamlDefaults;

/**
 * jar에 들어 있는 기본 콘텐츠(저장소의 content/)를 콘텐츠 폴더에 맞춘다.
 *
 * <p>빌드 때 content/가 {@code harbor-content/} 리소스로 들어가고, 파일 목록은 {@value #INDEX}에 적힌다. 서버가 쓴 기본 파일의
 * 해시를 {@value #MANIFEST}에 기록해 두고, 시작할 때마다 (추가 제안):
 *
 * <ul>
 *   <li>없는 파일은 만든다.
 *   <li>운영자가 고치지 않은 파일(해시가 기록과 같음)은 새 기본값으로 바꾼다. 기본값에서 빠진 파일이면 지운다.
 *   <li>운영자가 고친 파일은 그대로 두고 목록으로 알려 준다. 단 맵 형식 YAML(messages_ko.yml · balance.yml 등)이면 새 기본값에 새로 생긴
 *       키만 그 파일에 끼워 넣는다 ({@link YamlDefaults}). 지난 기본값의 키 목록을 {@value #KEYS}에 남겨서, 운영자가 일부러 지운 키는
 *       되살리지 않는다 (기록이 없으면 빠진 키를 모두 채운다).
 *   <li>기록이 없는 옛 폴더(이 기능 전에 만든 폴더)는 기본값과 다른 파일을 백업 폴더로 옮긴 뒤 새 기본값으로 바꾼다.
 * </ul>
 */
public final class DefaultContent {

    /** 기본 콘텐츠 파일 목록 리소스 (한 줄에 content/ 기준 상대 경로 하나). */
    public static final String INDEX = "harbor-content.txt";

    /** 기본 콘텐츠 리소스 경로 접두사. */
    public static final String PREFIX = "harbor-content/";

    /** 서버가 쓴 기본 파일 기록 (콘텐츠 폴더 안, 한 줄에 "sha256 경로"). */
    public static final String MANIFEST = ".harbor-defaults";

    /** 지난 기본값의 키 목록 (한 줄에 "경로\t키"). */
    public static final String KEYS = ".harbor-default-keys";

    /** 리소스 열기 (플러그인에서는 {@code JavaPlugin::getResource}). */
    @FunctionalInterface
    public interface Resources {
        @Nullable InputStream open(String path) throws IOException;
    }

    /**
     * 맞춘 결과.
     *
     * @param keptModified 운영자가 고쳐서 그대로 둔 파일 (새 기본값이 따로 있음)
     * @param backup 옛 폴더를 바꾸면서 원래 파일을 옮겨 둔 곳 (없으면 null)
     * @param filled 고친 파일에 새로 채운 키 (파일 → 키 경로)
     */
    public record SyncResult(
            int created,
            int updated,
            int removed,
            List<String> keptModified,
            @Nullable Path backup,
            Map<String, List<String>> filled) {}

    private DefaultContent() {}

    /** 콘텐츠 폴더를 jar의 기본 콘텐츠에 맞춘다. */
    public static SyncResult sync(Resources resources, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path manifestFile = root.resolve(MANIFEST);
        boolean legacy = Files.notExists(manifestFile) && hasYaml(root);
        Map<String, String> manifest = readManifest(manifestFile);
        Path keysFile = root.resolve(KEYS);
        Map<String, Set<String>> knownKeys = readKeys(keysFile);
        Map<String, Set<String>> nextKeys = new LinkedHashMap<>();
        Map<String, List<String>> filled = new LinkedHashMap<>();
        Path backupRoot =
                root.resolveSibling(
                        "content-backup-"
                                + LocalDateTime.now(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        boolean backedUp = false;
        int created = 0;
        int updated = 0;
        int removed = 0;
        List<String> kept = new ArrayList<>();

        Set<String> entries = new LinkedHashSet<>(index(resources));
        for (String entry : entries) {
            Path file = resolve(root, entry);
            byte[] data = read(resources, entry);
            String hash = sha256(data);
            String defaultText =
                    entry.endsWith(".yml") ? new String(data, StandardCharsets.UTF_8) : null;
            if (defaultText != null) {
                nextKeys.put(entry, YamlDefaults.keys(defaultText));
            }
            if (Files.notExists(file)) {
                write(file, data);
                manifest.put(entry, hash);
                created++;
                continue;
            }
            String current = sha256(Files.readAllBytes(file));
            if (current.equals(hash)) {
                manifest.put(entry, hash);
                continue;
            }
            String recorded = manifest.get(entry);
            if (current.equals(recorded) || legacy) {
                if (legacy) {
                    Path backup = resolve(backupRoot, entry);
                    Path parent = backup.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    backedUp = true;
                }
                write(file, data);
                manifest.put(entry, hash);
                updated++;
            } else {
                kept.add(entry);
                if (defaultText != null) {
                    Set<String> before = knownKeys.get(entry);
                    String mine = Files.readString(file, StandardCharsets.UTF_8);
                    YamlDefaults.Result result =
                            YamlDefaults.fill(
                                    mine, defaultText, k -> before == null || !before.contains(k));
                    if (result.changed()) {
                        Files.writeString(file, result.text(), StandardCharsets.UTF_8);
                        filled.put(entry, result.added());
                    }
                }
            }
        }
        for (String entry : List.copyOf(manifest.keySet())) {
            if (entries.contains(entry)) {
                continue;
            }
            Path file = resolve(root, entry);
            if (Files.exists(file)
                    && sha256(Files.readAllBytes(file)).equals(manifest.get(entry))) {
                Files.delete(file);
                removed++;
            } else if (Files.exists(file)) {
                kept.add(entry);
            }
            manifest.remove(entry);
        }
        writeManifest(manifestFile, manifest);
        writeKeys(keysFile, nextKeys);
        return new SyncResult(
                created,
                updated,
                removed,
                List.copyOf(kept),
                backedUp ? backupRoot : null,
                Map.copyOf(filled));
    }

    private static Path resolve(Path root, String entry) throws IOException {
        Path file = root.resolve(entry).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IOException("기본 콘텐츠 목록에 잘못된 경로가 있습니다: " + entry);
        }
        return file;
    }

    private static byte[] read(Resources resources, String entry) throws IOException {
        try (InputStream in = resources.open(PREFIX + entry)) {
            if (in == null) {
                throw new IOException("기본 콘텐츠 파일이 jar에 없습니다: " + entry);
            }
            return in.readAllBytes();
        }
    }

    private static void write(Path file, byte[] data) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, data);
    }

    private static boolean hasYaml(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> p.toString().endsWith(".yml"));
        }
    }

    private static Map<String, String> readManifest(Path file) throws IOException {
        Map<String, String> manifest = new LinkedHashMap<>();
        if (Files.notExists(file)) {
            return manifest;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space > 0 && space < line.length() - 1) {
                manifest.put(line.substring(space + 1), line.substring(0, space));
            }
        }
        return manifest;
    }

    private static void writeManifest(Path file, Map<String, String> manifest) throws IOException {
        StringBuilder out =
                new StringBuilder("# Harbor-Core가 쓴 기본 콘텐츠 해시. 지우면 다음 시작 때 옛 폴더로 보고 백업 후 새로 맞춘다\n");
        manifest.forEach((path, hash) -> out.append(hash).append(' ').append(path).append('\n'));
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }

    private static Map<String, Set<String>> readKeys(Path file) throws IOException {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        if (Files.notExists(file)) {
            return keys;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (!line.startsWith("#") && tab > 0) {
                keys.computeIfAbsent(line.substring(0, tab), k -> new LinkedHashSet<>())
                        .add(line.substring(tab + 1));
            }
        }
        return keys;
    }

    private static void writeKeys(Path file, Map<String, Set<String>> keys) throws IOException {
        StringBuilder out =
                new StringBuilder(
                        "# 지난 기본 콘텐츠의 키 목록. 고친 파일에 새 키만 채울 때 쓴다 (지우면 다음 시작 때 빠진 키를 모두 채운다)\n");
        keys.forEach(
                (path, set) ->
                        set.forEach(k -> out.append(path).append('\t').append(k).append('\n')));
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> index(Resources resources) throws IOException {
        try (InputStream in = resources.open(INDEX)) {
            if (in == null) {
                throw new IOException("기본 콘텐츠 목록(" + INDEX + ")이 jar에 없습니다");
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            }
        }
    }
}
