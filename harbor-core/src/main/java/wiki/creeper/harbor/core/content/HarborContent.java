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

/** 콘텐츠 로더 (YAML → 메모리 레지스트리). 읽기·검사는 가상 스레드에서, 스냅샷 교체만 메인 스레드에서 한다. 교체는 참조 하나를 바꾸는 것이라 틱 지연이 없다. */
public final class HarborContent implements ContentService {

    private final ContentLoader loader = new ContentLoader();
    private final Path root;
    private final Tasks tasks;
    private final Logger log;
    private volatile ContentSnapshot snapshot = ContentSnapshot.empty();

    // 시작 단계: 모듈이 등록했지만 아직 읽지 않은 형식. 모듈이 enable 안에서 바로 콘텐츠를 읽어도 보이도록 첫 snapshot() 때 읽는다
    private volatile boolean pending;
    private final Set<String> pendingTypes = new HashSet<>(); // this로 보호
    private final Set<String> owedEventTypes = new HashSet<>(); // 메인 스레드가 아닌 곳에서 읽어 아직 이벤트를 못 보낸 형식
    private @Nullable ValidationErrors pendingErrors; // this로 보호

    public HarborContent(Path root, Tasks tasks, Logger log) {
        this.root = root;
        this.tasks = tasks;
        this.log = log;
        CoreContent.register(loader);
        // 서버 API가 필요한 검사 (harbor-tools에서는 건너뜀)
        loader.addCheck(ItemDef.TYPE, (item, ctx) -> checkMaterial(item.baseItem(), ctx));
        loader.addCheck(ItemDef.TYPE, (item, ctx) -> checkModel(item.model(), ctx));
        loader.addCheck(FishDef.TYPE, (fish, ctx) -> checkModel(fish.model(), ctx));
        loader.addCheck(RigDef.TYPE, HarborContent::checkRigParts);
        loader.addCheck(SoundTable.TYPE, HarborContent::checkSoundFallbacks);
    }

    /**
     * 모듈의 콘텐츠 형식 등록 (시작 단계). 다음 {@link #snapshot()} 호출 또는 {@link #finishPending()}에서 전체를 다시 읽어
     * 반영한다.
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
     * 모듈 enable이 끝날 때 (메인 스레드): 남은 등록 형식을 읽고, 메인 스레드 밖에서 읽느라 못 보낸 {@link ContentReloadedEvent}를
     * 보낸다.
     *
     * @return 등록 뒤 읽기에서 난 오류 (없으면 null). 오류는 이미 로그에 남겼다
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

    /** 서버 시작 중: 전체를 읽고 바로 적용한다. 오류가 있으면 Err (서버 시작 중단). 메인 스레드에서 블로킹하지만 시작 단계라 허용한다. */
    public Result<ContentSnapshot, ValidationErrors> loadBlocking() {
        Result<ContentSnapshot, ValidationErrors> result = loader.load(root);
        if (result
                instanceof Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot loaded)) {
            apply(loaded, allTypes());
        }
        return result;
    }

    @Override
    public CompletableFuture<Result<ContentSnapshot, ValidationErrors>> reload(
            Set<String> types, boolean apply) {
        Set<String> only = types.isEmpty() ? allTypes() : Set.copyOf(types);
        ContentSnapshot previous = snapshot;
        return tasks.supplyAsync(() -> loader.reload(root, only, previous))
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
    }

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

    /** sounds.yml의 바닐라 대체 소리가 서버 소리 목록에 있는지 (minecraft: 키만, '15. 사운드'). */
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

    /** 리그 부품의 item_model 키와 블록 상태가 서버에서 읽히는지. */
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
