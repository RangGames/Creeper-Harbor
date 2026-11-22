package wiki.creeper.harbor.core.api;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;

/** Content registry. Snapshots are immutable, so any thread may read them. */
public interface ContentService {

    /**
     * The snapshot currently in effect. It changes on reload, so don't hold on to it; call this
     * each time you need it.
     */
    ContentSnapshot snapshot();

    Path root();

    /**
     * Reloads content. Reading and validation run on a virtual thread; only if there are no errors
     * is the snapshot swapped on the main thread and ContentReloadedEvent fired.
     *
     * @param types names of the types to reload (empty = all)
     * @param apply if false, only validates (/harbor validate)
     */
    CompletableFuture<Result<ContentSnapshot, ValidationErrors>> reload(
            Set<String> types, boolean apply);
}
