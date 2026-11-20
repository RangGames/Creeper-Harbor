package wiki.creeper.harbor.quest.editor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.quest.content.QuestDef;

/**
 * 편집기 파일 쓰기 (plugins/Harbor/content/quests/*.yml). 파일 읽기·쓰기는 가상 스레드, 결과는 메인 스레드. 저장은 한 번에 하나씩: 블록
 * 교체 → 쓰기 → quest 형식만 리로드 → 오류가 있으면 원래 글로 되돌리고 오류를 돌려준다 (서버 스냅샷은 리로드가 거부되면 그대로다).
 *
 * <p>서버에서 고친 파일은 DefaultContent가 "고친 파일"로 보고 기본값으로 덮어쓰지 않는다. 저장소 content/에 옮기는 것은 사람이 한다.
 */
public final class EditorStore {

    /** 저장 결과. ok면 errors는 비어 있다. */
    public record Outcome(boolean ok, String errors) {}

    /** 찾은 항목. */
    public record Found(String file, Map<String, Object> entry) {}

    private final ContentService content;
    private final Tasks tasks;
    private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);

    public EditorStore(ContentService content, Tasks tasks) {
        this.content = content;
        this.tasks = tasks;
    }

    public Path questsDir() {
        return content.root().resolve("quests");
    }

    /** 그 ID 항목이 든 파일을 찾는다. */
    public CompletableFuture<Optional<Found>> find(String id) {
        Path dir = questsDir();
        return tasks.supplyAsync(
                        () -> {
                            for (Path file : files(dir)) {
                                String text = read(file);
                                if (QuestYaml.contains(text, id)) {
                                    return QuestYaml.read(text, id)
                                            .map(e -> new Found(file.getFileName().toString(), e));
                                }
                            }
                            return Optional.<Found>empty();
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    /** ID가 이미 어느 파일에든 있는지. */
    public CompletableFuture<Boolean> exists(String id) {
        return find(id).thenApply(Optional::isPresent);
    }

    public CompletableFuture<Outcome> save(String file, String id, Map<String, Object> entry) {
        String text = QuestYaml.emit(entry);
        return write(file, old -> QuestYaml.replace(old, id, text));
    }

    public CompletableFuture<Outcome> delete(String file, String id) {
        return write(file, old -> QuestYaml.remove(old, id));
    }

    /** 저장 미리보기 글 (채팅). */
    public static String preview(Map<String, Object> entry) {
        return QuestYaml.emit(entry);
    }

    private synchronized CompletableFuture<Outcome> write(
            String file, java.util.function.UnaryOperator<String> change) {
        Path path = questsDir().resolve(file).normalize();
        CompletableFuture<Outcome> next =
                chain.handle((r, e) -> null)
                        .thenCompose(
                                ignored ->
                                        tasks.supplyAsync(
                                                () -> {
                                                    if (!path.startsWith(questsDir().normalize())) {
                                                        throw new IllegalArgumentException(
                                                                "quests 폴더 밖 파일: " + file);
                                                    }
                                                    String old =
                                                            Files.exists(path) ? read(path) : "";
                                                    writeText(path, change.apply(old));
                                                    return old;
                                                }))
                        .thenCompose(
                                old ->
                                        content.reload(Set.of(QuestDef.TYPE.name()), true)
                                                .thenCompose(result -> settle(path, old, result)))
                        .exceptionally(
                                e -> new Outcome(false, String.valueOf(rootCause(e).getMessage())))
                        .thenApplyAsync(r -> r, tasks.main());
        chain = next;
        return next;
    }

    private CompletableFuture<Outcome> settle(
            Path path, String old, Result<ContentSnapshot, ValidationErrors> result) {
        if (result instanceof Result.Err<ContentSnapshot, ValidationErrors> err) {
            String errors = err.error().format();
            return tasks.supplyAsync(
                    () -> {
                        writeText(path, old);
                        return new Outcome(false, errors);
                    });
        }
        return CompletableFuture.completedFuture(new Outcome(true, ""));
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        for (int i = 0; i < 8; i++) {
            Throwable cause = t.getCause();
            if (cause == null) {
                break;
            }
            t = cause;
        }
        return t;
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeText(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".editing");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
