package wiki.creeper.harbor.core.content;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
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
    }

    /** 모듈의 콘텐츠 형식 등록. 등록 후 {@link #loadBlocking()} 또는 reload로 다시 읽어야 반영된다. */
    public void register(ContentType<?> type) {
        loader.register(type);
    }

    public <T> void addCheck(ContentType<T> type, ContentCheck<T> check) {
        loader.addCheck(type, check);
    }

    @Override
    public ContentSnapshot snapshot() {
        return snapshot;
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
