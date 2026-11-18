package wiki.creeper.harbor.core.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * jar에 들어 있는 기본 콘텐츠(저장소의 content/)를 콘텐츠 폴더에 풀어 놓는다.
 *
 * <p>빌드 때 content/가 {@code harbor-content/} 리소스로 들어가고, 파일 목록은 {@value #INDEX}에 적힌다. 이미 있는 파일은 덮어쓰지
 * 않는다 (서버에서 고친 콘텐츠를 지키고, 업데이트로 새로 생긴 파일만 채운다).
 */
public final class DefaultContent {

    /** 기본 콘텐츠 파일 목록 리소스 (한 줄에 content/ 기준 상대 경로 하나). */
    public static final String INDEX = "harbor-content.txt";

    /** 기본 콘텐츠 리소스 경로 접두사. */
    public static final String PREFIX = "harbor-content/";

    /** 리소스 열기 (플러그인에서는 {@code JavaPlugin::getResource}). */
    @FunctionalInterface
    public interface Resources {
        @Nullable InputStream open(String path) throws IOException;
    }

    private DefaultContent() {}

    /**
     * 콘텐츠 폴더에 없는 기본 파일만 만든다.
     *
     * @return 새로 만든 파일 수
     * @throws IOException 목록이 jar에 없거나, 목록의 파일이 없거나, 쓰기에 실패했을 때
     */
    public static int extractMissing(Resources resources, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        int created = 0;
        for (String entry : index(resources)) {
            Path file = root.resolve(entry).normalize();
            if (!file.startsWith(root) || file.equals(root)) {
                throw new IOException("기본 콘텐츠 목록에 잘못된 경로가 있습니다: " + entry);
            }
            if (Files.exists(file)) {
                continue;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = resources.open(PREFIX + entry)) {
                if (in == null) {
                    throw new IOException("기본 콘텐츠 파일이 jar에 없습니다: " + entry);
                }
                Files.copy(in, file);
            }
            created++;
        }
        return created;
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
