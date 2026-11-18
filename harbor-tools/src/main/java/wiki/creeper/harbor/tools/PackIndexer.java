package wiki.creeper.harbor.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * 리소스팩(폴더 또는 zip)에서 item_model·소리 목록을 뽑아 content/resource_pack.yml을 만든다 ({@code harbor-tools
 * pack-index}).
 *
 * <ul>
 *   <li>모델: {@code assets/<ns>/items/<경로>.json} → {@code <ns>:<경로>}
 *   <li>소리: {@code assets/<ns>/sounds.json}의 키 → {@code <ns>:<키>}
 * </ul>
 */
final class PackIndexer {

    record Index(Set<String> models, Set<String> sounds) {}

    private PackIndexer() {}

    static Index read(Path pack) throws IOException {
        if (Files.isRegularFile(pack)) {
            try (FileSystem zip = FileSystems.newFileSystem(pack)) {
                return readRoot(zip.getPath("/"));
            }
        }
        return readRoot(pack);
    }

    private static Index readRoot(Path root) throws IOException {
        Path assets = findAssets(root);
        if (assets == null) {
            throw new IOException("assets 폴더를 찾지 못했습니다 (리소스팩 폴더나 zip을 주세요)");
        }
        Set<String> models = new TreeSet<>();
        Set<String> sounds = new TreeSet<>();
        try (Stream<Path> namespaces = Files.list(assets)) {
            for (Path ns : namespaces.filter(Files::isDirectory).toList()) {
                String namespace = name(ns);
                Path items = ns.resolve("items");
                if (Files.isDirectory(items)) {
                    try (Stream<Path> files = Files.walk(items)) {
                        files.filter(f -> Files.isRegularFile(f) && name(f).endsWith(".json"))
                                .forEach(f -> models.add(namespace + ":" + key(items, f)));
                    }
                }
                Path soundsJson = ns.resolve("sounds.json");
                if (Files.isRegularFile(soundsJson)) {
                    try (Reader reader =
                            Files.newBufferedReader(soundsJson, StandardCharsets.UTF_8)) {
                        JsonElement json = JsonParser.parseReader(reader);
                        if (json.isJsonObject()) {
                            json.getAsJsonObject()
                                    .keySet()
                                    .forEach(k -> sounds.add(namespace + ":" + k));
                        }
                    }
                }
            }
        }
        return new Index(models, sounds);
    }

    /** 팩 루트의 assets, 없으면 한 단계 아래 폴더(예: resourcepack/assets)에서 찾는다. */
    private static @Nullable Path findAssets(Path root) throws IOException {
        Path direct = root.resolve("assets");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                Path nested = child.resolve("assets");
                if (Files.isDirectory(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String key(Path items, Path file) {
        String relative = items.relativize(file).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".json".length());
    }

    private static String name(Path path) {
        Path file = path.getFileName();
        return file == null ? "" : file.toString().replace("/", "");
    }

    /** resource_pack.yml 내용. 팩의 위치(로컬 경로)는 적지 않는다. */
    static String yaml(Index index, String packName) {
        StringBuilder out = new StringBuilder();
        out.append("# 리소스팩에 들어 있는 item_model·소리 목록 (Core ItemModels가 이 목록에 있는 모델만 쓴다).\n");
        out.append("# 손으로 고치지 않는다. 팩을 바꾸면 다시 만든다: ./gradlew packIndex -Ppack=<리소스팩 폴더 또는 zip>\n");
        out.append("# 만든 팩: ").append(packName).append('\n');
        out.append("models:\n");
        index.models().forEach(m -> out.append("  - ").append(m).append('\n'));
        out.append("sounds:\n");
        index.sounds().forEach(s -> out.append("  - ").append(s).append('\n'));
        return out.toString();
    }
}
