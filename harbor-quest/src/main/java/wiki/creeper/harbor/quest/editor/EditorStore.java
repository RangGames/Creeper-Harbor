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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.quest.content.QuestDef;

/**
 * Editor file writes (plugins/Harbor/content/quests/*.yml). File reads and writes run on virtual
 * threads, results on the main thread. Saves run one at a time: replace the block → write → reload
 * only the quest type → on errors restore the original text and return the errors (the server
 * snapshot stays as is when the reload is refused).
 *
 * <p>DefaultContent treats a file edited on the server as an "edited file" and does not overwrite
 * it with the default. Copying it to the repository's content/ is done by a person.
 */
public final class EditorStore {

    /** Save result. errors is empty when ok. */
    public record Outcome(boolean ok, String errors) {}

    /** A found entry. */
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

    /** Finds the file that contains the entry with that ID. */
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

    /** Whether the ID already exists in any file. */
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

    /** Save preview text (chat). */
    public static String preview(Map<String, Object> entry) {
        return QuestYaml.emit(entry);
    }

    private synchronized CompletableFuture<Outcome> write(
            String file, java.util.function.UnaryOperator<String> change) {
        Path path = questsDir().resolve(file).normalize();
        // original state to restore: null before writing, the original text after writing (an empty
        // Optional if the file did not exist = delete it on restore)
        AtomicReference<@Nullable Optional<String>> before = new AtomicReference<>();
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
                                                    Optional<String> old =
                                                            Files.exists(path)
                                                                    ? Optional.of(read(path))
                                                                    : Optional.empty();
                                                    writeText(path, change.apply(old.orElse("")));
                                                    before.set(old);
                                                    return old;
                                                }))
                        .thenCompose(
                                old ->
                                        content.reload(Set.of(QuestDef.TYPE.name()), true)
                                                .thenCompose(result -> settle(path, old, result)))
                        .exceptionally(
                                e -> {
                                    // even if the reload ends with an exception (validator error
                                    // etc.), never leave
                                    // the broken text on disk
                                    Optional<String> old = before.get();
                                    if (old != null) {
                                        try {
                                            restore(path, old);
                                        } catch (RuntimeException restoreError) {
                                            e.addSuppressed(restoreError);
                                        }
                                    }
                                    return new Outcome(
                                            false, String.valueOf(rootCause(e).getMessage()));
                                })
                        .thenApplyAsync(r -> r, tasks.main());
        chain = next;
        return next;
    }

    private CompletableFuture<Outcome> settle(
            Path path, Optional<String> old, Result<ContentSnapshot, ValidationErrors> result) {
        if (result instanceof Result.Err<ContentSnapshot, ValidationErrors> err) {
            String errors = err.error().format();
            return tasks.supplyAsync(
                    () -> {
                        restore(path, old);
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

    /**
     * Rolls back a failed save. A file that did not exist before is deleted: restoring empty text
     * would leave an empty file, and an empty file is an "empty file" error, so every later quest
     * reload (and the load on the next start) would be refused.
     */
    private static void restore(Path path, Optional<String> old) {
        if (old.isPresent()) {
            writeText(path, old.get());
            return;
        }
        try {
            Files.deleteIfExists(path);
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
