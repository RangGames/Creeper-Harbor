package wiki.creeper.harbor.core.rig;

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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
 * Display rig playback ('16. Plugin design' 5.10). Bakes rigs (on a virtual thread, whenever
 * content changes), spawns them, and sends animation commands every tick on the main thread.
 *
 * <ul>
 *   <li>Budget: balance.yml rig.tick_budget_ms per tick. Once exceeded, remaining rigs wait for the
 *       next tick. Playback time is computed from the server tick number, so deferred rigs land in
 *       the right place on their next update
 *   <li>LOD: not updated when nobody is watching or the nearest viewer is beyond rig.lod_distance;
 *       resynced at the current position once close again
 *   <li>Cleanup: all entities are non-persistent and tagged with PDC harbor:rig. On chunk unload
 *       the rig is removed; on startup and chunk load, orphaned tagged entities are removed
 * </ul>
 */
public final class RigManager implements RigService, Listener {

    /** Rig entity tag (value = instance ID). */
    static final NamespacedKey RIG_KEY = new NamespacedKey(HarborId.DEFAULT_NAMESPACE, "rig");

    private final Plugin plugin;
    private final Supplier<ContentSnapshot> content;
    private final Tasks tasks;
    private final Logger log;
    private final Map<UUID, CoreRig> instances = new LinkedHashMap<>();

    /** Entity UUID → rig. */
    private final Map<UUID, CoreRig> byEntity = new HashMap<>();

    /** Interaction entity UUID → hitbox index. */
    private final Map<UUID, Integer> hitboxIndex = new HashMap<>();

    /** Baked rigs (filled on a virtual thread, read on the main thread). */
    private volatile Map<RigId, BakedRig> baked = Map.of();

    private final Map<RigId, BakedRig> bakedOnDemand = new ConcurrentHashMap<>();
    private volatile RigSettings settings;
    private long now;
    private int cursor;
    private long lastTickNanos;
    private int lastDeferred;
    private @Nullable BukkitTask task;
    private final RigEntitySpawner spawner;

    public RigManager(Plugin plugin, Supplier<ContentSnapshot> content, Tasks tasks, Logger log) {
        this.plugin = plugin;
        this.content = content;
        this.tasks = tasks;
        this.log = log;
        this.settings = RigSettings.from(content.get().single(Balance.TYPE));
        this.spawner = new RigEntitySpawner(content);
    }

    /** Starts the tick task, bakes rigs and cleans up leftover rig entities. */
    public void start() {
        rebake(content.get());
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                removeIfOrphan(e);
            }
        }
        task = tasks.timer(1, 1, this::tick);
    }

    /** Removes all rigs (Core shutdown). */
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

    /** Rig clock (incremented by 1 each time the tick task runs). */
    long now() {
        return now;
    }

    // ------------------------------------------------------------------ Baking

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

    /** The baked rig. Bakes it right away if the async bake hasn't finished yet. */
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
            // Rigs already spawned keep playing with the old bake (respawning them is up to each
            // module)
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

        RigEntitySpawner.Spawned entities =
                spawner.spawn(
                        world, base, rig, playback, options, visible, instanceId, moveTicks, now);
        ItemDisplay root = entities.root();
        Display[] parts = entities.parts();
        Interaction[] hitboxes = entities.hitboxes();

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

    // ------------------------------------------------------------------ Tick

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

    /** Update time of the last tick (nanoseconds). */
    long lastTickNanos() {
        return lastTickNanos;
    }

    /** Number of rigs deferred in the last tick due to the budget. */
    int lastDeferred() {
        return lastDeferred;
    }

    /** Removes a rig (idempotent). */
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

    // ------------------------------------------------------------------ Cleanup

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
            // Don't remove entities during unload handling; clean up the whole rig next tick
            // (including hitboxes spanning other chunks)
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

    // ------------------------------------------------------------------ Hitboxes

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        CoreRig rig = byEntity.get(clicked.getUniqueId());
        if (rig == null) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) {
            return; // The same click arrives twice, so only pass on the main hand's plain
            // interaction
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

    // ------------------------------------------------------------------ For admin commands

    /** The rig nearest to this location (within the radius). */
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

    /** Removes rigs within the radius. All of them if radius is negative. */
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

    /** Total entity count (roots, parts, hitboxes). */
    int entityCount() {
        return byEntity.size();
    }

    /** Key count per animation of a baked rig (to check compression). */
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
