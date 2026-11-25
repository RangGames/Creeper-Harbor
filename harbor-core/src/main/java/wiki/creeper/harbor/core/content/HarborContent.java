package wiki.creeper.harbor.core.content;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Content loader (YAML → in-memory registry). Reading and validation run on virtual threads; only
 * the snapshot swap happens on the main thread. The swap replaces a single reference, so it causes
 * no tick lag.
 */
public final class HarborContent implements ContentService {

    private final ContentLoader loader = new ContentLoader();
    private final Path root;
    private final Tasks tasks;
    private final Logger log;
    private volatile ContentSnapshot snapshot = ContentSnapshot.empty();

    // Startup: types registered by modules but not read yet. Read on the first snapshot() so a
    // module reading content right inside enable still sees them
    private volatile boolean pending;
    private final Set<String> pendingTypes = new HashSet<>(); // guarded by this
    private final Set<String> owedEventTypes =
            new HashSet<>(); // types read off the main thread whose event has not been fired yet
    private @Nullable ValidationErrors pendingErrors; // guarded by this

    public HarborContent(Path root, Tasks tasks, Logger log) {
        this.root = root;
        this.tasks = tasks;
        this.log = log;
        CoreContent.register(loader);
        // Checks that need the server API (skipped in harbor-tools)
        loader.addCheck(ItemDef.TYPE, (item, ctx) -> checkMaterial(item.baseItem(), ctx));
        loader.addCheck(ItemDef.TYPE, HarborContent::checkRemainder);
        loader.addCheck(ItemDef.TYPE, (item, ctx) -> checkModel(item.model(), ctx));
        loader.addCheck(FishDef.TYPE, (fish, ctx) -> checkModel(fish.model(), ctx));
        loader.addCheck(RigDef.TYPE, HarborContent::checkRigParts);
        loader.addCheck(SoundTable.TYPE, HarborContent::checkSoundFallbacks);
    }

    /**
     * Registers a module's content type (startup). Everything is re-read and applied on the next
     * {@link #snapshot()} call or in {@link #finishPending()}.
     */
    public synchronized void register(ContentType<?> type) {
        loader.register(type);
        pendingTypes.add(type.name());
        pending = true;
    }

    public <T> void addCheck(ContentType<T> type, ContentCheck<T> check) {
        loader.addCheck(type, check);
    }

    @Override
    public ContentSnapshot snapshot() {
        if (pending) {
            loadPending();
        }
        return snapshot;
    }

    /**
     * When module enable finishes (main thread): reads the remaining registered types and fires any
     * {@link ContentReloadedEvent} that could not be fired because the read happened off the main
     * thread.
     *
     * @return errors from the post-registration read (null if none). Errors have already been
     *     logged
     */
    public synchronized @Nullable ValidationErrors finishPending() {
        loadPending();
        if (pendingErrors == null && !owedEventTypes.isEmpty()) {
            Set<String> types = Set.copyOf(owedEventTypes);
            owedEventTypes.clear();
            Bukkit.getPluginManager().callEvent(new ContentReloadedEvent(snapshot, types));
        }
        ValidationErrors errors = pendingErrors;
        pendingErrors = null;
        return errors;
    }

    private synchronized void loadPending() {
        if (!pending) {
            return;
        }
        pending = false;
        Set<String> added = Set.copyOf(pendingTypes);
        pendingTypes.clear();
        Result<ContentSnapshot, ValidationErrors> result = loader.load(root);
        switch (result) {
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot loaded) -> {
                if (Bukkit.isPrimaryThread()) {
                    apply(loaded, added);
                } else {
                    snapshot = loaded;
                    log.info("콘텐츠 적용: {}", loaded.counts());
                    owedEventTypes.addAll(added);
                }
            }
            case Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors) -> {
                pendingErrors = errors;
                log.error("콘텐츠 오류 {}건 ({}):\n{}", errors.size(), added, errors.format());
            }
        }
    }

    @Override
    public Path root() {
        return root;
    }

    /**
     * During server startup: reads everything and applies it immediately. Err on errors (aborts
     * server startup). Blocks the main thread, which is acceptable during startup.
     */
    public Result<ContentSnapshot, ValidationErrors> loadBlocking() {
        Result<ContentSnapshot, ValidationErrors> result = loader.load(root);
        if (result
                instanceof Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot loaded)) {
            apply(loaded, allTypes());
        }
        return result;
    }

    @Override
    public synchronized CompletableFuture<Result<ContentSnapshot, ValidationErrors>> reload(
            Set<String> types, boolean apply) {
        Set<String> only = types.isEmpty() ? allTypes() : Set.copyOf(types);
        // Serialize reloads: when they overlapped, the one finishing later was built on the
        // snapshot from before the earlier reload, silently reverting its changes
        // (e.g. /harbor reload fish and a quest editor save). Start from the snapshot after the
        // earlier reload was applied
        CompletableFuture<Result<ContentSnapshot, ValidationErrors>> next =
                reloadChain
                        .handle((r, e) -> null)
                        .thenCompose(
                                ignored -> {
                                    ContentSnapshot previous = snapshot;
                                    return tasks.supplyAsync(
                                            () -> loader.reload(root, only, previous));
                                })
                        .thenApplyAsync(
                                result -> {
                                    if (apply
                                            && result
                                                    instanceof
                                                    Result.Ok<ContentSnapshot, ValidationErrors>(
                                                            ContentSnapshot loaded)) {
                                        apply(loaded, only);
                                    }
                                    return result;
                                },
                                tasks.main());
        reloadChain = next;
        return next;
    }

    private CompletableFuture<?> reloadChain = CompletableFuture.completedFuture(null);

    public Set<String> allTypes() {
        Set<String> names = new HashSet<>();
        loader.types().forEach(t -> names.add(t.name()));
        return names;
    }

    private void apply(ContentSnapshot loaded, Set<String> reloaded) {
        snapshot = loaded;
        log.info("콘텐츠 적용: {}", loaded.counts());
        Bukkit.getPluginManager().callEvent(new ContentReloadedEvent(loaded, reloaded));
    }

    /**
     * Whether sounds.yml's vanilla fallback sounds exist in the server's sound list (minecraft:
     * keys only, '15. Sound').
     */
    private static void checkSoundFallbacks(SoundTable table, ContentCheck.Context ctx) {
        Registry<Sound> sounds =
                RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
        table.events()
                .forEach(
                        (name, event) -> {
                            String fallback = event.fallback();
                            if (fallback == null || !Key.parseable(fallback)) {
                                return;
                            }
                            Key key = Key.key(fallback);
                            if (key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                                    && sounds.get(key) == null) {
                                ctx.error(name + ": 대체 소리 '" + fallback + "'는 바닐라 소리가 아닙니다");
                            }
                        });
    }

    private static void checkMaterial(String baseItem, ContentCheck.Context ctx) {
        Material material = Material.matchMaterial(baseItem);
        if (material == null || !material.isItem()) {
            ctx.error("base_item '" + baseItem + "'는 바닐라 아이템이 아닙니다");
        }
    }

    /** components.use_remainder must be a vanilla item. */
    private static void checkRemainder(ItemDef item, ContentCheck.Context ctx) {
        String remainder = item.components().useRemainder();
        if (remainder == null) {
            return;
        }
        Material material = Material.matchMaterial(remainder);
        if (material == null || !material.isItem()) {
            ctx.error("components.use_remainder '" + remainder + "'는 바닐라 아이템이 아닙니다");
        }
    }

    /** Whether rig parts' item_model keys and block states can be parsed by the server. */
    private static void checkRigParts(RigDef rig, ContentCheck.Context ctx) {
        for (RigDef.Bone bone : rig.bones()) {
            RigDef.Part part = bone.part();
            if (part == null) {
                continue;
            }
            switch (part.kind()) {
                case ITEM -> checkModel(part.model(), ctx);
                case BLOCK -> {
                    try {
                        var _ = Bukkit.createBlockData(part.model());
                    } catch (IllegalArgumentException e) {
                        ctx.error(
                                "뼈 '"
                                        + bone.name()
                                        + "'의 block '"
                                        + part.model()
                                        + "'는 블록 상태가 아닙니다 (예: minecraft:kelp_plant)");
                    }
                }
            }
        }
    }

    private static void checkModel(String model, ContentCheck.Context ctx) {
        try {
            var _ = Key.key(model);
        } catch (RuntimeException e) {
            ctx.error("model '" + model + "'는 'harbor:fish/harbor_mackerel' 같은 키여야 합니다");
        }
    }
}
