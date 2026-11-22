package wiki.creeper.harbor.npc.entity;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.api.event.PlayerFlagChangedEvent;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.npc.NpcKeys;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.api.MarkerProvider;
import wiki.creeper.harbor.npc.api.NpcInfo;
import wiki.creeper.harbor.npc.api.NpcMarkerRegistry;
import wiki.creeper.harbor.npc.api.NpcService;
import wiki.creeper.harbor.npc.api.QuestMarker;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.content.Spot;

/**
 * NPC entity spawning · cleanup · gaze · name variants · quest markers (docs 20 5.4). Entities are
 * created when a chunk's entities load and removed when they unload. Main thread only.
 */
public final class NpcManager implements NpcService, NpcMarkerRegistry, Listener {

    /** Supplies the current location of wandering NPCs (implemented by WandererManager). */
    public interface Placement {
        /** Where the NPC should stand now. Empty means hidden. */
        Optional<Spot> spot(NpcDef def);
    }

    private record Registered<T>(Plugin owner, T value) {}

    private record BusyCheck(Plugin owner, String reason, Predicate<Player> test) {}

    private final Plugin plugin;
    private final HarborApi api;
    private final Worlds worlds;
    private final Logger log;
    private final boolean entitiesEnabled;
    private final Map<NpcId, SpawnedNpc> spawned = new HashMap<>();

    /** World name → chunk key → NPCs in that chunk (spatial index for gaze). */
    private final Map<String, Map<Long, List<SpawnedNpc>>> index = new HashMap<>();

    private final List<Registered<MarkerProvider>> providers = new CopyOnWriteArrayList<>();
    private final List<BusyCheck> busyChecks = new CopyOnWriteArrayList<>();

    /** Markers currently shown to each player. */
    private final Map<UUID, Map<NpcId, QuestMarker>> shown = new HashMap<>();

    private Placement placement = def -> Optional.ofNullable(def.location());

    /** Before start(), chunk-load events do not spawn (leftover cleanup comes first). */
    private boolean started;

    public NpcManager(
            Plugin plugin, HarborApi api, Worlds worlds, Logger log, boolean entitiesEnabled) {
        this.plugin = plugin;
        this.api = api;
        this.worlds = worlds;
        this.log = log;
        this.entitiesEnabled = entitiesEnabled;
    }

    public void setPlacement(Placement placement) {
        this.placement = placement;
    }

    public NpcBalance balance() {
        return new NpcBalance(
                api.content()
                        .snapshot()
                        .single(wiki.creeper.harbor.common.content.type.Balance.TYPE));
    }

    public Optional<NpcDef> def(NpcId id) {
        return api.content().snapshot().get(NpcDef.TYPE, id.id());
    }

    public Collection<NpcDef> defs() {
        return api.content().snapshot().all(NpcDef.TYPE).values();
    }

    /**
     * Right after server start: clean up leftovers + spawn NPCs in loaded chunks. Starts the gaze
     * timer.
     */
    public void start() {
        if (!entitiesEnabled) {
            return;
        }
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (NpcKeys.tagged(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("이전 실행의 NPC·컷씬 엔티티 {}개를 정리했습니다", removed);
        }
        started = true;
        syncAll();
        int interval = balance().lookIntervalTicks();
        var _ = Bukkit.getScheduler().runTaskTimer(plugin, this::lookTick, interval, interval);
    }

    public void shutdown() {
        spawned.values().forEach(SpawnedNpc::remove);
        spawned.clear();
        index.clear();
    }

    // ------------------------------------------------------------------ spawning · removal

    /** Brings every NPC in line with content · flags · schedules. */
    public void syncAll() {
        if (!entitiesEnabled || !started) {
            return;
        }
        Map<NpcId, NpcDef> defs = new HashMap<>();
        defs().forEach(d -> defs.put(d.id(), d));
        for (NpcId id : List.copyOf(spawned.keySet())) {
            if (!defs.containsKey(id)) {
                despawn(id, false);
            }
        }
        defs.values().forEach(this::sync);
    }

    public void sync(NpcId id) {
        def(id).ifPresentOrElse(this::sync, () -> despawn(id, false));
    }

    /** Recreates the NPC (/harbor npc respawn). */
    public void respawn(NpcId id) {
        despawn(id, false);
        sync(id);
    }

    /** Where the NPC should stand (applies spawn · hide flags and the wanderer schedule). */
    public Optional<Location> desired(NpcDef def) {
        String spawnFlag = def.spawnFlag();
        if (spawnFlag != null && !api.flags().hasServer(spawnFlag)) {
            return Optional.empty();
        }
        String hideFlag = def.hideFlag();
        if (hideFlag != null && api.flags().hasServer(hideFlag)) {
            return Optional.empty();
        }
        return placement.spot(def).flatMap(worlds::location);
    }

    /** Recreates the NPC if it is not where it should be or its content changed. */
    private void sync(NpcDef def) {
        Optional<Location> want = desired(def);
        SpawnedNpc current = spawned.get(def.id());
        if (want.isEmpty()) {
            if (current != null) {
                despawn(def.id(), def.wanderer());
            }
            return;
        }
        Location at = want.get();
        if (current != null) {
            boolean same =
                    current.home().getWorld().equals(at.getWorld())
                            && current.home().distanceSquared(at) < 0.01
                            && current.def().equals(def)
                            && current.body().isValid();
            if (same) {
                return;
            }
            despawn(def.id(), false);
        }
        if (at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            spawn(def, at);
        }
    }

    private void spawn(NpcDef def, Location at) {
        NpcBalance balance = balance();
        try {
            SpawnedNpc npc = SpawnedNpcFactory.create(api, def, at, balance);
            spawned.put(def.id(), npc);
            index.computeIfAbsent(at.getWorld().getName(), k -> new HashMap<>())
                    .computeIfAbsent(npc.chunkKey(), k -> new ArrayList<>())
                    .add(npc);
        } catch (RuntimeException e) {
            log.error("NPC {} 생성 실패", def.id(), e);
        }
    }

    /**
     * @param fog vanish with fog particles (conditional wanderer exit)
     */
    public void despawn(NpcId id, boolean fog) {
        SpawnedNpc npc = spawned.remove(id);
        if (npc == null) {
            return;
        }
        Map<Long, List<SpawnedNpc>> chunks = index.get(npc.home().getWorld().getName());
        if (chunks != null) {
            List<SpawnedNpc> list = chunks.get(npc.chunkKey());
            if (list != null) {
                list.remove(npc);
                if (list.isEmpty()) {
                    chunks.remove(npc.chunkKey());
                }
            }
        }
        if (fog && npc.body().isValid()) {
            npc.home()
                    .getWorld()
                    .spawnParticle(
                            Particle.CLOUD,
                            npc.home().clone().add(0, 1, 0),
                            40,
                            0.4,
                            0.8,
                            0.4,
                            0.02);
        }
        npc.remove();
        shown.values().forEach(m -> m.remove(id));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!entitiesEnabled || !started) {
            return;
        }
        // clean up tagged entities left by a crash (ones that are not ours)
        for (Entity e : event.getEntities()) {
            String id = NpcKeys.get(e, NpcKeys.NPC);
            if (id != null && !isOurs(e, id)) {
                e.remove();
            } else if (id == null && NpcKeys.get(e, NpcKeys.CUTSCENE) != null) {
                e.remove();
            } else if (NpcKeys.get(e, NpcKeys.SPEECH) != null) {
                e.remove(); // speech bubbles are never saved, so one loaded with a chunk is
                // a leftover
            }
        }
        Chunk chunk = event.getChunk();
        for (NpcDef def : defs()) {
            if (spawned.containsKey(def.id())) {
                continue;
            }
            desired(def)
                    .filter(
                            l ->
                                    l.getWorld().equals(chunk.getWorld())
                                            && (l.getBlockX() >> 4) == chunk.getX()
                                            && (l.getBlockZ() >> 4) == chunk.getZ())
                    .ifPresent(l -> spawn(def, l));
        }
    }

    private boolean isOurs(Entity e, String id) {
        SpawnedNpc npc = spawned.get(NpcId.parse(id));
        return npc != null
                && npc.entities().stream().anyMatch(x -> x.getUniqueId().equals(e.getUniqueId()));
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Map<Long, List<SpawnedNpc>> chunks = index.get(chunk.getWorld().getName());
        if (chunks == null) {
            return;
        }
        List<SpawnedNpc> list = chunks.get(SpawnedNpc.chunkKey(chunk.getX(), chunk.getZ()));
        if (list != null) {
            List.copyOf(list).forEach(n -> despawn(n.def().id(), false));
        }
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        syncAll();
    }

    @EventHandler
    public void onServerFlag(ServerFlagChangedEvent event) {
        for (NpcDef def : defs()) {
            if (event.flag().equals(def.spawnFlag()) || event.flag().equals(def.hideFlag())) {
                sync(def);
            }
        }
    }

    @EventHandler
    public void onPlayerFlag(PlayerFlagChangedEvent event) {
        Player player = Bukkit.getPlayer(event.player());
        if (player == null) {
            return;
        }
        for (SpawnedNpc npc : spawned.values()) {
            boolean relevant =
                    npc.def().nameVariants().stream()
                            .anyMatch(v -> v.playerFlag().equals(event.flag()));
            if (relevant && npc.body().entity().getTrackedBy().contains(player)) {
                updateViewer(player, npc);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrack(PlayerTrackEntityEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof org.bukkit.entity.Mannequin)) {
            return;
        }
        String id = NpcKeys.get(entity, NpcKeys.NPC);
        if (id == null) {
            return;
        }
        Player player = event.getPlayer();
        // pick the markers on the next tick, once tracking is settled
        var _ =
                Bukkit.getScheduler()
                        .runTask(
                                plugin,
                                () -> {
                                    SpawnedNpc npc = spawned.get(NpcId.parse(id));
                                    if (npc != null && player.isOnline()) {
                                        updateViewer(player, npc);
                                    }
                                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        providers.removeIf(r -> r.owner().equals(event.getPlugin()));
        busyChecks.removeIf(b -> b.owner().equals(event.getPlugin()));
    }

    // ------------------------------------------------------------------ name variants · quest
    // markers

    /** Name variant index (0 = default). First match from the top. */
    private int variantIndex(Player player, NpcDef def) {
        List<NpcDef.NameVariant> variants = def.nameVariants();
        for (int i = 0; i < variants.size(); i++) {
            if (api.flags().hasPlayer(player.getUniqueId(), variants.get(i).playerFlag())) {
                return i + 1;
            }
        }
        return 0;
    }

    private void updateViewer(Player player, SpawnedNpc npc) {
        if (npc.names().size() > 1) {
            int chosen = variantIndex(player, npc.def());
            for (int i = 0; i < npc.names().size(); i++) {
                show(player, npc.names().get(i), i == chosen);
            }
        }
        QuestMarker marker = marker(player, npc.def().id());
        Map<NpcId, QuestMarker> mine =
                shown.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        mine.put(npc.def().id(), marker);
        npc.markers().forEach((type, display) -> show(player, display, type == marker));
    }

    private void show(Player player, Entity entity, boolean visible) {
        if (visible) {
            player.showEntity(plugin, entity);
        } else {
            player.hideEntity(plugin, entity);
        }
    }

    @Override
    public void register(Plugin owner, MarkerProvider provider) {
        providers.add(new Registered<>(owner, provider));
    }

    @Override
    public void refresh(Player player) {
        for (SpawnedNpc npc : spawned.values()) {
            if (npc.body().entity().getTrackedBy().contains(player)) {
                updateViewer(player, npc);
            }
        }
    }

    @Override
    public void refresh(Player player, NpcId npc) {
        SpawnedNpc s = spawned.get(npc);
        if (s != null && s.body().entity().getTrackedBy().contains(player)) {
            updateViewer(player, s);
        }
    }

    @Override
    public QuestMarker marker(Player player, NpcId npc) {
        QuestMarker best = QuestMarker.NONE;
        for (Registered<MarkerProvider> r : providers) {
            try {
                best = QuestMarker.max(best, r.value().marker(player, npc));
            } catch (RuntimeException e) {
                log.warn("퀘스트 표시 계산 오류 ({}, {})", r.owner().getName(), npc, e);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ gaze

    private void lookTick() {
        NpcLook.tick(spawned.values(), index);
    }

    // ------------------------------------------------------------------ NpcService

    /** The NPC of a clicked entity (both the body and the hitbox). */
    public Optional<NpcId> npcOf(Entity entity) {
        String id = NpcKeys.get(entity, NpcKeys.NPC);
        if (id == null) {
            return Optional.empty();
        }
        try {
            NpcId npc = NpcId.parse(id);
            return spawned.containsKey(npc) ? Optional.of(npc) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Collection<NpcId> spawnedIds() {
        return List.copyOf(spawned.keySet());
    }

    @Override
    public Optional<NpcInfo> npc(NpcId id) {
        return def(id).map(this::info);
    }

    private NpcInfo info(NpcDef d) {
        return new NpcInfo(
                d.id(), d.name(), d.role(), d.dialog(), d.shop(), spawned.containsKey(d.id()));
    }

    @Override
    public Collection<NpcInfo> all() {
        return defs().stream().map(this::info).toList();
    }

    @Override
    public Optional<Location> location(NpcId id) {
        SpawnedNpc npc = spawned.get(id);
        if (npc != null) {
            return Optional.of(npc.home().clone());
        }
        return def(id).flatMap(this::desired);
    }

    @Override
    public Optional<NpcInfo> nearest(Player player, double radius) {
        Location loc = Worlds.loc(player);
        NpcInfo best = null;
        double bestD = radius * radius;
        for (SpawnedNpc npc : spawned.values()) {
            if (!npc.home().getWorld().equals(loc.getWorld())) {
                continue;
            }
            double d = npc.home().distanceSquared(loc);
            if (d <= bestD) {
                bestD = d;
                best = info(npc.def());
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public boolean isNear(Player player, NpcId id, double radius) {
        return location(id)
                .filter(l -> l.getWorld().equals(player.getWorld()))
                .map(l -> l.distanceSquared(Worlds.loc(player)) <= radius * radius)
                .orElse(false);
    }

    @Override
    public Component displayName(Player viewer, NpcId id) {
        return def(id).map(d -> mini(nameFor(viewer, d))).orElse(Component.text(id.toString()));
    }

    /** Raw name shown to this player (MiniMessage). */
    public String nameFor(Player viewer, NpcDef def) {
        int i = variantIndex(viewer, def);
        return i == 0 ? def.name() : def.nameVariants().get(i - 1).name();
    }

    @Override
    public void registerBusyCheck(Plugin owner, String reason, Predicate<Player> busy) {
        busyChecks.add(new BusyCheck(owner, reason, busy));
    }

    @Override
    public Optional<String> busyReason(Player player) {
        for (BusyCheck check : busyChecks) {
            try {
                if (check.test().test(player)) {
                    return Optional.of(check.reason());
                }
            } catch (RuntimeException e) {
                log.warn("상태 검사 오류 ({})", check.owner().getName(), e);
            }
        }
        return Optional.empty();
    }

    static Component mini(String text) {
        return MiniMessage.miniMessage().deserialize(text);
    }

    public @Nullable Location home(NpcId id) {
        SpawnedNpc npc = spawned.get(id);
        return npc == null ? null : npc.home().clone();
    }
}
