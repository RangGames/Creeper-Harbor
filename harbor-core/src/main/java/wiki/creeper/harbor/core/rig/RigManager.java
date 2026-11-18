package wiki.creeper.harbor.core.rig;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.BakedRig;
import wiki.creeper.harbor.common.rig.RigBaker;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.rig.RigPlayback;
import wiki.creeper.harbor.common.rig.RigSampler;
import wiki.creeper.harbor.core.api.RigHit;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.RigService;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 디스플레이 리그 재생 ('16. 플러그인 설계' 5.10). 리그를 굽고(가상 스레드, 콘텐츠가 바뀔 때마다), 띄우고, 메인 스레드에서 틱마다 애니메이션 명령을 보낸다.
 *
 * <ul>
 *   <li>예산: 틱당 balance.yml rig.tick_budget_ms. 넘으면 남은 리그는 다음 틱으로 미룬다. 재생 시각은 서버 틱 번호로 계산하므로 미뤄진 리그도
 *       다음 갱신에서 제자리를 찾는다
 *   <li>LOD: 아무도 안 보거나 가장 가까운 시청자가 rig.lod_distance 밖이면 갱신하지 않고, 다시 가까워지면 지금 위치에서 재동기화한다
 *   <li>정리: 모든 엔티티는 저장하지 않고 PDC harbor:rig 표시를 단다. 청크 언로드 때 그 리그를 지우고, 시작·청크 적재 때 주인 없는 표시 엔티티를 지운다
 * </ul>
 */
public final class RigManager implements RigService, Listener {

    /** 리그 엔티티 표시 (값 = 인스턴스 ID). */
    static final NamespacedKey RIG_KEY = new NamespacedKey(HarborId.DEFAULT_NAMESPACE, "rig");

    private final Plugin plugin;
    private final Supplier<ContentSnapshot> content;
    private final Tasks tasks;
    private final Logger log;
    private final Map<UUID, CoreRig> instances = new LinkedHashMap<>();

    /** 엔티티 UUID → 리그. */
    private final Map<UUID, CoreRig> byEntity = new HashMap<>();

    /** Interaction 엔티티 UUID → 판정 상자 번호. */
    private final Map<UUID, Integer> hitboxIndex = new HashMap<>();

    /** 구운 리그 (가상 스레드에서 채우고 메인 스레드에서 읽는다). */
    private volatile Map<RigId, BakedRig> baked = Map.of();

    private final Map<RigId, BakedRig> bakedOnDemand = new ConcurrentHashMap<>();
    private volatile RigSettings settings;
    private long now;
    private int cursor;
    private long lastTickNanos;
    private int lastDeferred;
    private @Nullable BukkitTask task;

    public RigManager(Plugin plugin, Supplier<ContentSnapshot> content, Tasks tasks, Logger log) {
        this.plugin = plugin;
        this.content = content;
        this.tasks = tasks;
        this.log = log;
        this.settings = RigSettings.from(content.get().single(Balance.TYPE));
    }

    /** 틱 작업 시작, 리그 굽기, 남은 리그 엔티티 정리. */
    public void start() {
        rebake(content.get());
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                removeIfOrphan(e);
            }
        }
        task = tasks.timer(1, 1, this::tick);
    }

    /** 모든 리그를 지운다 (Core 종료). */
    public void shutdown() {
        BukkitTask t = task;
        if (t != null) {
            t.cancel();
            task = null;
        }
        for (CoreRig rig : new ArrayList<>(instances.values())) {
            remove(rig, RigInstance.RemoveReason.SHUTDOWN);
        }
    }

    static void requireMain() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("리그는 메인 스레드에서만 다룰 수 있습니다");
        }
    }

    Plugin plugin() {
        return plugin;
    }

    RigSettings settings() {
        return settings;
    }

    /** 리그 시계 (틱 작업이 한 번 돌 때마다 1씩 오른다). */
    long now() {
        return now;
    }

    // ------------------------------------------------------------------ 굽기

    private void rebake(ContentSnapshot snapshot) {
        RigSettings next = RigSettings.from(snapshot.single(Balance.TYPE));
        settings = next;
        bakedOnDemand.clear();
        Collection<RigDef> defs = snapshot.all(RigDef.TYPE).values();
        var _ =
                tasks.supplyAsync(
                                () -> {
                                    Map<RigId, BakedRig> result = new HashMap<>();
                                    for (RigDef def : defs) {
                                        result.put(def.id(), RigBaker.bake(def, next.bake()));
                                    }
                                    return Map.copyOf(result);
                                })
                        .thenAcceptAsync(
                                result -> {
                                    baked = result;
                                    bakedOnDemand.clear();
                                    log.info("리그 {}개를 구웠습니다", result.size());
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.error("리그 굽기 실패", e);
                                    return null;
                                });
    }

    /** 구운 리그. 아직 비동기 굽기가 안 끝났으면 바로 굽는다. */
    private Optional<BakedRig> bakedRig(RigId id) {
        Optional<RigDef> def = definition(id);
        if (def.isEmpty()) {
            return Optional.empty();
        }
        BakedRig ready = baked.get(id);
        if (ready != null && ready.def().equals(def.get())) {
            return Optional.of(ready);
        }
        return Optional.of(
                bakedOnDemand.computeIfAbsent(id, k -> RigBaker.bake(def.get(), settings.bake())));
    }

    @EventHandler
    public void onContentReloaded(ContentReloadedEvent event) {
        Set<String> types = event.reloadedTypes();
        if (types.contains(RigDef.TYPE.name()) || types.contains(Balance.TYPE.name())) {
            // 이미 떠 있는 리그는 예전 구운 결과로 계속 재생한다 (다시 띄우는 것은 각 모듈 몫)
            rebake(event.snapshot());
        }
    }

    // ------------------------------------------------------------------ RigService

    @Override
    public Set<RigId> ids() {
        Set<RigId> ids = new TreeSet<>((a, b) -> a.id().compareTo(b.id()));
        for (HarborId id : content.get().ids(RigDef.TYPE.name())) {
            ids.add(new RigId(id));
        }
        return Collections.unmodifiableSet(ids);
    }

    @Override
    public Optional<RigDef> definition(RigId id) {
        return content.get().get(RigDef.TYPE, id.id());
    }

    @Override
    public RigInstance spawn(RigId id, Location at, RigOptions options) {
        requireMain();
        BakedRig rig = bakedRig(id).orElseThrow(() -> new IllegalArgumentException("없는 리그: " + id));
        World world = Objects.requireNonNull(at.getWorld(), "리그 위치에 월드가 없습니다");
        RigSettings s = settings;
        RigDef def = rig.def();
        int moveTicks = options.moveTicks() >= 0 ? options.moveTicks() : s.moveTicks();
        boolean visible = options.isPublic();
        UUID instanceId = UUID.randomUUID();

        RigPlayback playback = new RigPlayback(rig);
        String animation =
                options.animation() != null ? options.animation() : def.defaultAnimation();
        if (animation != null) {
            Optional<BakedRig.BakedAnimation> anim = rig.animation(animation);
            if (anim.isEmpty()) {
                throw new IllegalArgumentException("리그 " + id + "에 동작 '" + animation + "'가 없습니다");
            }
            int frames = anim.get().frameCount();
            int phase =
                    options.phase() < 0
                            ? ThreadLocalRandom.current().nextInt(Math.max(1, frames - 1))
                            : options.phase();
            var _ = playback.play(animation, 0, 0, now);
            playback.setPhase(phase, now);
        }

        float[] heading = RigSampler.entityHeading(def, at.getYaw(), at.getPitch());
        Location base = at.clone();
        base.setYaw(heading[0]);
        base.setPitch(heading[1]);

        ItemDisplay root =
                world.spawn(
                        base,
                        ItemDisplay.class,
                        e -> {
                            prepare(e, visible, instanceId);
                            e.setItemStack(ItemStack.empty());
                            e.setTeleportDuration(moveTicks);
                        });
        List<Entity> spawned = new ArrayList<>();
        spawned.add(root);
        Display[] parts = new Display[rig.parts().size()];
        Interaction[] hitboxes =
                options.onHit() == null
                        ? new Interaction[0]
                        : new Interaction[rig.hitboxes().size()];
        try {
            for (int p = 0; p < parts.length; p++) {
                parts[p] =
                        spawnPart(
                                world,
                                base,
                                rig,
                                p,
                                playback,
                                options,
                                visible,
                                instanceId,
                                moveTicks);
                spawned.add(parts[p]);
                var _ = root.addPassenger(parts[p]);
            }
            for (int h = 0; h < hitboxes.length; h++) {
                RigDef.Hitbox box = rig.hitboxes().get(h).hitbox();
                double k = options.scale() * def.scale();
                hitboxes[h] =
                        world.spawn(
                                base,
                                Interaction.class,
                                e -> {
                                    prepare(e, visible, instanceId);
                                    e.setInteractionWidth((float) (box.width() * k));
                                    e.setInteractionHeight((float) (box.height() * k));
                                    e.setResponsive(true);
                                });
                spawned.add(hitboxes[h]);
            }
        } catch (RuntimeException e) {
            spawned.forEach(Entity::remove);
            throw e;
        }

        CoreRig instance =
                new CoreRig(
                        this,
                        instanceId,
                        rig,
                        playback,
                        root,
                        parts,
                        hitboxes,
                        options.scale(),
                        options.onHit(),
                        options.viewers(),
                        at.getYaw(),
                        at.getPitch(),
                        moveTicks);
        instances.put(instanceId, instance);
        for (Entity e : instance.entities()) {
            byEntity.put(e.getUniqueId(), instance);
        }
        for (int h = 0; h < hitboxes.length; h++) {
            hitboxIndex.put(hitboxes[h].getUniqueId(), h);
        }
        Set<UUID> viewers = options.viewers();
        if (viewers != null) {
            for (UUID v : viewers) {
                Player p = Bukkit.getPlayer(v);
                if (p != null) {
                    for (Entity e : instance.entities()) {
                        p.showEntity(plugin, e);
                    }
                }
            }
        }
        instance.updateHitboxes(now);
        return instance;
    }

    private Display spawnPart(
            World world,
            Location base,
            BakedRig rig,
            int index,
            RigPlayback playback,
            RigOptions options,
            boolean visible,
            UUID instanceId,
            int moveTicks) {
        BakedRig.PartRef ref = rig.parts().get(index);
        RigDef.Part part = ref.part();
        Consumer<Display> common =
                d -> {
                    prepare(d, visible, instanceId);
                    d.setTeleportDuration(moveTicks);
                    d.setInterpolationDuration(0);
                    d.setViewRange((float) rig.def().viewRange());
                    d.setTransformation(
                            CoreRig.toBukkit(
                                    playback.pose(index, now), options.scale(), part.flip()));
                    if (options.blockLight() >= 0) {
                        d.setBrightness(
                                new Display.Brightness(options.blockLight(), options.skyLight()));
                    } else if (part.brightness() instanceof RigDef.Brightness(int block, int sky)) {
                        d.setBrightness(new Display.Brightness(block, sky));
                    }
                };
        return switch (part.kind()) {
            case ITEM ->
                    world.spawn(
                            base,
                            ItemDisplay.class,
                            d -> {
                                common.accept(d);
                                ItemStack stack = ItemStack.of(Material.PAPER);
                                stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(part.model()));
                                d.setItemStack(stack);
                                d.setItemDisplayTransform(
                                        ItemDisplay.ItemDisplayTransform.valueOf(
                                                part.transform().name()));
                            });
            case BLOCK ->
                    world.spawn(
                            base,
                            BlockDisplay.class,
                            d -> {
                                common.accept(d);
                                d.setBlock(Bukkit.createBlockData(part.model()));
                            });
        };
    }

    private static void prepare(Entity e, boolean visible, UUID instanceId) {
        e.setPersistent(false);
        e.setVisibleByDefault(visible);
        e.setSilent(true);
        e.getPersistentDataContainer()
                .set(RIG_KEY, PersistentDataType.STRING, instanceId.toString());
    }

    @Override
    public Collection<? extends RigInstance> instances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    @Override
    public Optional<RigInstance> byEntity(Entity entity) {
        return Optional.ofNullable(byEntity.get(entity.getUniqueId()));
    }

    @Override
    public int visibleParts(Player player) {
        int count = 0;
        for (CoreRig rig : instances.values()) {
            if (rig.isValid() && rig.root().getTrackedBy().contains(player)) {
                count += rig.parts().length;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 틱

    private void tick() {
        now++;
        int n = instances.size();
        if (n == 0) {
            lastTickNanos = 0;
            lastDeferred = 0;
            return;
        }
        RigSettings s = settings;
        List<CoreRig> list = new ArrayList<>(instances.values());
        long start = System.nanoTime();
        int begin = cursor % n;
        int done = 0;
        for (; done < n; done++) {
            CoreRig rig = list.get((begin + done) % n);
            if (rig.isValid()) {
                rig.tick(now, s);
            }
            if (System.nanoTime() - start > s.tickBudgetNanos()) {
                done++;
                break;
            }
        }
        cursor = (begin + done) % Math.max(1, instances.size());
        lastDeferred = n - done;
        lastTickNanos = System.nanoTime() - start;
    }

    /** 지난 틱 갱신 시간 (나노초). */
    long lastTickNanos() {
        return lastTickNanos;
    }

    /** 지난 틱 예산 초과로 미룬 리그 수. */
    int lastDeferred() {
        return lastDeferred;
    }

    /** 리그를 지운다 (멱등). */
    void remove(CoreRig rig, RigInstance.RemoveReason reason) {
        if (instances.remove(rig.id()) == null) {
            return;
        }
        for (Entity e : rig.entities()) {
            byEntity.remove(e.getUniqueId());
            hitboxIndex.remove(e.getUniqueId());
        }
        rig.destroy(reason, log);
    }

    // ------------------------------------------------------------------ 정리

    private void removeIfOrphan(Entity e) {
        String tag = e.getPersistentDataContainer().get(RIG_KEY, PersistentDataType.STRING);
        if (tag != null && !byEntity.containsKey(e.getUniqueId())) {
            e.remove();
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            removeIfOrphan(e);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        Set<CoreRig> affected = new HashSet<>();
        for (Entity e : event.getEntities()) {
            CoreRig rig = byEntity.get(e.getUniqueId());
            if (rig != null) {
                affected.add(rig);
            }
        }
        if (!affected.isEmpty()) {
            // 언로드 처리 중에 엔티티를 지우지 않고 다음 틱에 리그째 정리한다 (다른 청크에 걸친 판정 상자 포함)
            var _ =
                    tasks.sync(
                            () ->
                                    affected.forEach(
                                            rig ->
                                                    remove(
                                                            rig,
                                                            RigInstance.RemoveReason
                                                                    .CHUNK_UNLOAD)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (CoreRig rig : instances.values()) {
            rig.forget(id);
        }
    }

    // ------------------------------------------------------------------ 판정 상자

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        CoreRig rig = byEntity.get(clicked.getUniqueId());
        if (rig == null) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) {
            return; // 같은 클릭이 두 번 오므로 주 손의 일반 상호작용만 넘긴다
        }
        hit(event.getPlayer(), rig, clicked, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Entity attacked = event.getAttacked();
        CoreRig rig = byEntity.get(attacked.getUniqueId());
        if (rig == null) {
            return;
        }
        event.setCancelled(true);
        hit(event.getPlayer(), rig, attacked, true);
    }

    private void hit(Player player, CoreRig rig, Entity entity, boolean attack) {
        Integer index = hitboxIndex.get(entity.getUniqueId());
        Consumer<RigHit> handler = rig.hitHandler();
        if (index == null || handler == null) {
            return;
        }
        try {
            handler.accept(new RigHit(player, rig, rig.hitboxBone(index), attack));
        } catch (RuntimeException e) {
            log.error("리그 판정 처리 중 오류 ({})", rig.rig(), e);
        }
    }

    // ------------------------------------------------------------------ 관리 명령용

    /** 이 위치에서 가장 가까운 리그 (반경 안). */
    Optional<CoreRig> nearest(Location at, double radius) {
        CoreRig best = null;
        double bestSq = radius * radius;
        for (CoreRig rig : instances.values()) {
            Location l = rig.location();
            if (rig.isValid() && Objects.equals(l.getWorld(), at.getWorld())) {
                double d = l.distanceSquared(at);
                if (d <= bestSq) {
                    bestSq = d;
                    best = rig;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** 반경 안의 리그를 지운다. radius가 음수면 전부. */
    int clear(@Nullable Location at, double radius) {
        int removed = 0;
        for (CoreRig rig : new ArrayList<>(instances.values())) {
            Location l = rig.location();
            boolean inside =
                    at == null
                            || radius < 0
                            || (Objects.equals(l.getWorld(), at.getWorld())
                                    && l.distanceSquared(at) <= radius * radius);
            if (inside) {
                remove(rig, RigInstance.RemoveReason.REQUESTED);
                removed++;
            }
        }
        return removed;
    }

    /** 엔티티 수 합계 (루트·부품·판정 상자). */
    int entityCount() {
        return byEntity.size();
    }

    /** 구운 리그의 동작별 키 수 (압축 확인용). */
    Map<String, Integer> keyCounts(RigId id) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        bakedRig(id)
                .ifPresent(
                        rig ->
                                rig.animations()
                                        .forEach(
                                                (name, anim) -> counts.put(name, anim.keyCount())));
        return counts;
    }
}
