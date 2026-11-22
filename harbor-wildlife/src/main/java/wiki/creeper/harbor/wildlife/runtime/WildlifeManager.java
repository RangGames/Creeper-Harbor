package wiki.creeper.harbor.wildlife.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;
import wiki.creeper.harbor.wildlife.api.WildFish;
import wiki.creeper.harbor.wildlife.api.WildHit;
import wiki.creeper.harbor.wildlife.api.WildlifeService;
import wiki.creeper.harbor.wildlife.api.event.WildFishSpawnEvent;
import wiki.creeper.harbor.wildlife.api.event.WildFishStartleEvent;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.Frame;
import wiki.creeper.harbor.wildlife.sim.PlayerSnap;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.wildlife.sim.Raycast;
import wiki.creeper.harbor.wildlife.sim.SimEvent;
import wiki.creeper.harbor.wildlife.sim.SpeciesProfile;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.FishingSpot;

/**
 * Main-thread wild fish management ('17-3' 9.4). Hands player state to the simulation thread
 * ({@link SimEngine}) and applies its output frames to rigs.
 *
 * <ul>
 *   <li>Thread stage 1: at the end of each tick, copies player snapshots (position, sneaking, boat)
 *       and hands them over
 *   <li>Thread stage 3: with hidden real entities, the main thread applies output every tick but
 *       defers to the next tick past a 1.0ms budget (round robin). If applying lags more than 2
 *       steps, the 16~40 block send interval goes from 4 to 6 ticks, and if it still lags the
 *       display cap is reduced (auto-throttle)
 *   <li>Display selection: every second, fills each player's budget (fish count, parts) with fish
 *       within 40 blocks, nearest first. The part budget is the smaller of the effects intensity
 *       50/75/100% and what's left of Core's 300 after other rigs (fishing shadows, aquariums,
 *       bosses)
 *   <li>Natural spawns: 12 fish target per player, 2 schools per second, 20 fish per server tick,
 *       2,000 fish total. Fish school spots get one extra school
 *   <li>Cleanup: rig entities are not saved and carry harbor:wild on the root. Orphans are removed
 *       on startup and chunk load
 * </ul>
 *
 * <p>This class only holds the tick order, last frame and tick count; rig display is handled by
 * {@link RigPresenter}, spawning by {@link WildSpawner}, density multipliers by {@link
 * SpawnBoosts}, and region boundaries by {@link RegionFence}.
 */
public final class WildlifeManager implements WildlifeService, Listener {

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final RigPresenter rigs;
    private final SpawnBoosts boosts = new SpawnBoosts();
    private final WildSpawner spawner;
    private final RegionFence fence;
    private Frame applied = Frame.EMPTY;
    private long tick;
    private @Nullable DebugOverlay debug;

    WildlifeManager(WildlifeKit kit, SimEngine engine) {
        this.kit = kit;
        this.engine = engine;
        this.rigs = new RigPresenter(kit, engine, this);
        this.spawner = new WildSpawner(kit, engine, this, boosts);
        this.fence = new RegionFence(kit, engine);
    }

    void debug(DebugOverlay overlay) {
        this.debug = overlay;
    }

    void start() {
        rigs.removeOrphans();
        spawner.loadAnchors();
        for (ActiveSeaEvent e : kit.seaEvents.active()) {
            seaEventStarted(e);
        }
        var _ = kit.tasks.timer(1, 1, this::tick);
    }

    void shutdown() {
        rigs.shutdown();
    }

    Frame frame() {
        return applied;
    }

    SimEngine engine() {
        return engine;
    }

    // ================================================================== tick

    private void tick() {
        tick++;
        WildlifeBalance bal = kit.balance();
        snapshotPlayers();
        pullFrame(bal);
        rigs.applyPending(bal);
        if (tick % 20 == 0) {
            rigs.viewerPass(bal, tick);
            spawner.refillCredits(bal);
            spawner.spotPass(bal);
            prune();
        }
        spawner.spawnPass(bal);
        fence.check(applied, tick);
    }

    /** Thread stage 1: copy player state. */
    private void snapshotPlayers() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<PlayerSnap> list = new ArrayList<>(online.size());
        for (Player p : online) {
            Location l = Locs.of(p);
            list.add(
                    new PlayerSnap(
                            p.getUniqueId(),
                            p.getWorld().getName(),
                            new Vec3(l.getX(), l.getY(), l.getZ()),
                            p.isSneaking(),
                            p.getVehicle() instanceof Boat,
                            p.getGameMode() != GameMode.SPECTATOR));
        }
        engine.players(list);
    }

    // ---------------------------------------------------------------- output apply

    private void pullFrame(WildlifeBalance bal) {
        Frame f = engine.latest();
        if (f.seq() == applied.seq()) {
            return;
        }
        rigs.measureLag(bal, tick);
        applied = f;
        for (SimEvent e : f.events()) {
            switch (e) {
                case SimEvent.Removed r -> destroy(r.fish());
                case SimEvent.Startle s -> startled(f, s);
                case SimEvent.Particle p -> rigs.particle(p, bal);
            }
        }
        rigs.queueAll();
    }

    private void startled(Frame f, SimEvent.Startle s) {
        Player p = Bukkit.getPlayer(s.player());
        FishOut o = f.get(s.fish());
        if (p != null && o != null) {
            WildFish view = view(o);
            if (view != null) {
                Bukkit.getPluginManager().callEvent(new WildFishStartleEvent(view, p));
            }
        }
    }

    private void destroy(UUID id) {
        rigs.destroy(id);
        fence.forget(id);
        DebugOverlay d = debug;
        if (d != null) {
            d.forget(id);
        }
    }

    private void prune() {
        spawner.pruneRecent();
        boosts.prune();
        fence.prune(tick);
    }

    // ---------------------------------------------------------------- diagnostics
    // (WildCommands, DebugOverlay)

    /** Number of fish currently shown with rigs, and their part count. */
    int[] shownStats() {
        return rigs.shownStats();
    }

    int ourParts(UUID player) {
        return rigs.ourParts(player);
    }

    int otherParts(UUID player) {
        return rigs.otherParts(player);
    }

    boolean visibleTo(UUID fish, UUID player) {
        return rigs.visibleTo(fish, player);
    }

    int degradeLevel() {
        return rigs.degradeLevel();
    }

    double lastApplyMillis() {
        return rigs.lastApplyMillis();
    }

    int deferred() {
        return rigs.deferred();
    }

    /** This player's current budget (fish count, parts). */
    int[] budgetOf(Player p) {
        return rigs.budgetOf(p);
    }

    int boostCount() {
        return boosts.count();
    }

    int anchorCount() {
        return spawner.anchorCount();
    }

    // ---------------------------------------------------------------- spawning (WildCommands)

    /**
     * Places a spawn order (empty list if WildFishSpawnEvent is cancelled).
     *
     * @return IDs of the created fish (the first is the leader)
     */
    List<UUID> order(
            SpeciesProfile p,
            Location at,
            int count,
            @Nullable ScriptedBehaviour scripted,
            WildFishSpawnEvent.Reason reason,
            @Nullable String tag,
            @Nullable RegionId region,
            List<Vec3> path) {
        return spawner.order(p, at, count, scripted, reason, tag, region, path);
    }

    /**
     * If the location is outside the medium, finds a medium cell within 3 cells up/down to spawn
     * in.
     */
    List<UUID> spawnAt(
            SpeciesProfile p,
            Location at,
            int count,
            @Nullable ScriptedBehaviour behaviour,
            WildFishSpawnEvent.Reason reason,
            List<Vec3> path) {
        return spawner.spawnAt(p, at, count, behaviour, reason, path);
    }

    // ---------------------------------------------------------------- fish schools and sea events
    // (WorldLink)

    void spotMoved(FishingSpot spot, @Nullable FishingSpot previous) {
        spawner.spotMoved(spot, previous);
    }

    /**
     * Great migration: target species density ×wildlifeBoost (multiplier set by World, balance
     * default ×2 if absent).
     */
    void seaEventStarted(ActiveSeaEvent e) {
        boosts.seaEventStarted(e);
    }

    void seaEventEnded(ActiveSeaEvent e) {
        boosts.seaEventEnded(e);
    }

    // ---------------------------------------------------------------- cleanup

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        rigs.removeOrphans(event.getEntities());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        rigs.dropViewer(event.getPlayer(), false, tick);
        UUID id = event.getPlayer().getUniqueId();
        spawner.forgetPlayer(id);
        rigs.forgetPlayer(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        rigs.dropViewer(event.getPlayer(), true, tick);
    }

    /**
     * Content reload: immediately recreates only species whose rig changed. The rest are replaced
     * naturally at the next cleanup.
     */
    void reloaded(ProfileIndex before, ProfileIndex after) {
        for (SpeciesProfile next : after.bySpecies().values()) {
            SpeciesProfile prev = before.bySpecies().get(next.species());
            if (prev != null && !prev.rig().equals(next.rig())) {
                engine.submit(world -> world.replaceProfile(next.species(), next));
                for (FishOut o : applied.fish()) {
                    if (o.profile().species().equals(next.species())) {
                        rigs.removeRigOf(o.id());
                    }
                }
            }
        }
        WildlifeBalance bal = kit.balance();
        engine.submit(world -> world.balance(bal));
        engine.period(bal.simPeriodMillis());
    }

    // ================================================================== WildlifeService

    private @Nullable WildFish view(FishOut o) {
        World w = Bukkit.getWorld(o.world());
        if (w == null) {
            return null;
        }
        return new WildFish(
                o.id(),
                o.profile().fish().id(),
                o.profile().species(),
                new Location(w, o.x(), o.y(), o.z(), o.yaw(), o.pitch()),
                o.state(),
                o.profile().medium(),
                o.school(),
                o.profile().radius(),
                rigs.displayed(o.id()),
                o.scripted());
    }

    @Override
    public List<WildFish> nearby(Location location, double radius) {
        World w = location.getWorld();
        if (w == null) {
            return List.of();
        }
        List<WildFish> result = new ArrayList<>();
        for (FishOut o :
                applied.nearby(
                        w.getName(), location.getX(), location.getY(), location.getZ(), radius)) {
            WildFish v = view(o);
            if (v != null) {
                result.add(v);
            }
        }
        return result;
    }

    @Override
    public Optional<WildFish> fish(UUID id) {
        FishOut o = applied.get(id);
        return o == null ? Optional.empty() : Optional.ofNullable(view(o));
    }

    /** For ShadowDriver.isValid: whether it is alive in the last frame. */
    boolean alive(UUID id) {
        FishOut o = applied.get(id);
        return o != null;
    }

    @Override
    public int count() {
        return applied.fish().size();
    }

    @Override
    public Optional<UUID> spawnScripted(FishId fish, Location at, ScriptedBehaviour behaviour) {
        SpeciesProfile p = kit.index().byFish().get(fish);
        if (p == null) {
            return Optional.empty();
        }
        List<UUID> ids =
                spawnAt(p, at, 1, behaviour, WildFishSpawnEvent.Reason.SCRIPTED, List.of());
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    @Override
    public Optional<UUID> spawnSchool(FishId fish, List<Location> path, int count) {
        SpeciesProfile p = kit.index().byFish().get(fish);
        if (p == null || path.isEmpty() || p.medium() == Medium.FLOOR) {
            return Optional.empty();
        }
        List<UUID> ids = spawner.spawnSchool(p, path, count);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    @Override
    public void boost(
            String source, RegionId region, Set<FishId> fish, double multiplier, Instant until) {
        boosts.put(source, region, fish, multiplier, until);
    }

    @Override
    public void clearBoost(String source) {
        boosts.remove(source);
    }

    @Override
    public Optional<WildHit> raycast(Location origin, Vector direction, double distance) {
        World w = origin.getWorld();
        if (w == null || direction.lengthSquared() < 1e-9 || distance <= 0) {
            return Optional.empty();
        }
        Vector d = direction.clone().normalize();
        double half = distance / 2;
        double cx = origin.getX() + d.getX() * half;
        double cy = origin.getY() + d.getY() * half;
        double cz = origin.getZ() + d.getZ() * half;
        FishOut best = null;
        double bestT = Double.MAX_VALUE;
        for (FishOut o : applied.nearby(w.getName(), cx, cy, cz, half + 3)) {
            if (o.hidden()) {
                continue;
            }
            double t =
                    Raycast.sphere(
                            origin.getX(),
                            origin.getY(),
                            origin.getZ(),
                            d.getX(),
                            d.getY(),
                            d.getZ(),
                            o.x(),
                            o.y(),
                            o.z(),
                            o.profile().radius(),
                            distance);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = o;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        WildFish v = view(best);
        if (v == null) {
            return Optional.empty();
        }
        Location point = origin.clone().add(d.clone().multiply(bestT));
        return Optional.of(new WildHit(v, point, bestT));
    }

    @Override
    public void remove(UUID fish) {
        engine.submit(world -> world.remove(fish, SimEvent.Reason.REQUESTED));
    }
}
