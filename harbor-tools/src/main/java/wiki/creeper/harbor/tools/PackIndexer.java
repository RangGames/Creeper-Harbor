package wiki.creeper.harbor.tools;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.pack.PackBuilder;

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
        Path packRoot = assets.getParent();
        PackBuilder.PackIndex index = PackBuilder.scan(packRoot == null ? root : packRoot);
        return new Index(new TreeSet<>(index.models()), new TreeSet<>(index.sounds()));
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
