package wiki.creeper.harbor.wildlife.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.Frame;
import wiki.creeper.harbor.wildlife.sim.SimEvent;
import wiki.creeper.harbor.wildlife.sim.SpeciesProfile;
import wiki.creeper.harbor.wildlife.sim.ViewBudget;

/**
 * Wild fish rig display ('17-3' 9.4 thread stage 3, display selection). Applies output frames to
 * rigs within budget, and every second picks display targets per player to show or hide rigs.
 * Raises the auto-throttle level when applying falls behind. Main thread only.
 */
final class RigPresenter {

    private static final String INTENSITY_FLAG = "fishing/settings/intensity";

    /** Display state of a visible (or briefly hidden) fish. */
    static final class Shown {
        @Nullable RigInstance rig;
        final Set<UUID> viewers = new HashSet<>();
        int oneShotSeq;
        int teleportSeq;
        long oneShotUntil;
        long hiddenSince = -1;
        float yaw;
        float pitch;
    }

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final WildlifeManager manager;
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final Set<UUID> rootIds = new HashSet<>();
    private final Set<UUID> applyQueue = new LinkedHashSet<>();
    private final Map<UUID, Integer> ourParts = new HashMap<>();
    private final Map<UUID, Integer> otherParts = new HashMap<>();
    private int lagCycles;
    private int degrade;
    private long calmSince;
    private long lastOtherParts;
    private long lastApplyNanos;
    private int lastDeferred;

    RigPresenter(WildlifeKit kit, SimEngine engine, WildlifeManager manager) {
        this.kit = kit;
        this.engine = engine;
        this.manager = manager;
    }

    void shutdown() {
        for (Shown s : shown.values()) {
            removeRig(s);
        }
        shown.clear();
        rootIds.clear();
    }

    // ---------------------------------------------------------------- output apply

    /**
     * On a new frame: if applying the previous frame wasn't finished, counts a lagged step and
     * adjusts the throttle level.
     */
    void measureLag(WildlifeBalance bal, long tick) {
        if (!applyQueue.isEmpty()) {
            lagCycles++;
        } else {
            lagCycles = 0;
        }
        if (lagCycles > bal.lagCycles()) {
            degrade = Math.min(2, degrade + 1);
            calmSince = tick;
            lagCycles = 0;
        } else if (lagCycles == 0 && degrade > 0 && tick - calmSince > 200) {
            degrade--;
            calmSince = tick;
        }
    }

    /** Queues every fish with a rig for applying. */
    void queueAll() {
        for (Map.Entry<UUID, Shown> e : shown.entrySet()) {
            if (e.getValue().rig != null) {
                applyQueue.add(e.getKey());
            }
        }
    }

    void applyPending(WildlifeBalance bal) {
        Frame applied = manager.frame();
        long start = System.nanoTime();
        long budget = (long) (bal.applyBudgetMillis() * 1_000_000);
        Iterator<UUID> it = applyQueue.iterator();
        while (it.hasNext()) {
            if (System.nanoTime() - start > budget) {
                break;
            }
            UUID id = it.next();
            it.remove();
            Shown s = shown.get(id);
            FishOut o = applied.get(id);
            if (s != null && o != null) {
                apply(s, o, bal);
            }
        }
        lastApplyNanos = System.nanoTime() - start;
        lastDeferred = applyQueue.size();
    }

    private void apply(Shown s, FishOut o, WildlifeBalance bal) {
        RigInstance rig = s.rig;
        if (rig == null) {
            return;
        }
        if (!rig.isValid()) {
            forgetRig(s);
            return;
        }
        if (o.hidden()) {
            removeRig(s);
            return;
        }
        World w = Bukkit.getWorld(o.world());
        if (w == null) {
            return;
        }
        Location loc = new Location(w, o.x(), o.y(), o.z(), o.yaw(), o.pitch());
        ViewBudget.Tier tier = ViewBudget.tier(o.nearest(), bal.nearDistance(), bal.showDistance());
        if (o.teleportSeq() != s.teleportSeq) {
            s.teleportSeq = o.teleportSeq();
            rig.moveTo(loc, 0);
        } else if (tier == ViewBudget.Tier.NEAR) {
            rig.moveTo(loc, bal.nearMoveTicks());
        } else {
            int every = degrade >= 1 ? 3 : 2;
            if (manager.frame().seq() % every == 0) {
                rig.moveTo(loc, degrade >= 1 ? bal.midMoveTicksSlow() : bal.midMoveTicks());
            }
        }
        if (Math.abs(o.yaw() - s.yaw) > 1 || Math.abs(o.pitch() - s.pitch) > 1) {
            s.yaw = o.yaw();
            s.pitch = o.pitch();
            rig.setHeading(o.yaw(), o.pitch());
        }
        if (o.oneShotSeq() != s.oneShotSeq) {
            s.oneShotSeq = o.oneShotSeq();
            String one = o.oneShot();
            if (one != null && rig.playOnce(one, o.animation().isEmpty() ? null : o.animation())) {
                s.oneShotUntil =
                        (long) Bukkit.getCurrentTick()
                                + o.profile().animations().getOrDefault(one, 20);
            }
        } else if (Bukkit.getCurrentTick() >= s.oneShotUntil
                && !o.animation().isEmpty()
                && !o.animation().equals(rig.animation())) {
            var _ = rig.play(o.animation());
        }
        if (Math.abs(rig.speed() - o.animationSpeed()) > 0.1) {
            rig.setSpeed(o.animationSpeed());
        }
    }

    /**
     * Action particles: only for players within 16 blocks ('17-3' 9.4 LOD skips 16~40 blocks). In
     * lava, smoke/lava particles instead of water drops.
     */
    void particle(SimEvent.Particle p, WildlifeBalance bal) {
        World w = Bukkit.getWorld(p.world());
        if (w == null) {
            return;
        }
        String key = p.key();
        if (p.medium() == Medium.LAVA) {
            key =
                    switch (key) {
                        case "splash" -> "lava";
                        case "bubble", "bubble_pop", "bubble_column_up", "fishing", "underwater" ->
                                "smoke";
                        default -> key;
                    };
        }
        Particle particle =
                Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
        if (particle == null || particle.getDataType() != Void.class) {
            return;
        }
        Location at = new Location(w, p.x(), p.y(), p.z());
        double near2 = bal.nearDistance() * bal.nearDistance();
        for (Player viewer : w.getPlayers()) {
            if (Locs.of(viewer).distanceSquared(at) <= near2) {
                viewer.spawnParticle(particle, at, 8, 0.2, 0.2, 0.2, 0.02);
            }
        }
    }

    // ---------------------------------------------------------------- display targets

    void viewerPass(WildlifeBalance bal, long tick) {
        Frame f = manager.frame();
        refreshOtherParts(bal, tick);
        Map<UUID, List<Player>> want = new HashMap<>();
        ourParts.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = Locs.of(p);
            List<FishOut> near =
                    f.nearby(
                            p.getWorld().getName(),
                            l.getX(),
                            l.getY(),
                            l.getZ(),
                            bal.keepDistance());
            if (near.isEmpty()) {
                continue;
            }
            UUID pid = p.getUniqueId();
            List<ViewBudget.Candidate> candidates = new ArrayList<>(near.size());
            Map<UUID, Integer> partsOf = new HashMap<>();
            for (FishOut o : near) {
                if (o.hidden()) {
                    continue;
                }
                Shown s = shown.get(o.id());
                boolean before = s != null && s.viewers.contains(pid);
                candidates.add(
                        new ViewBudget.Candidate(
                                o.id(),
                                o.distanceSq(l.getX(), l.getY(), l.getZ()),
                                o.profile().parts(),
                                before,
                                o.scripted()));
                partsOf.put(o.id(), o.profile().parts());
            }
            int count =
                    ViewBudget.countBudget(
                            bal.maxShownPerPlayer(), degrade >= 2 ? bal.lagShownScale() : 1.0);
            int parts =
                    ViewBudget.partBudget(
                            bal.maxPartsPerPlayer(),
                            bal.intensityScale(intensity(pid)),
                            bal.corePartBudget(),
                            otherParts.getOrDefault(pid, 0));
            List<UUID> chosen =
                    ViewBudget.select(
                            candidates, count, parts, bal.showDistance(), bal.keepDistance());
            int used = 0;
            for (UUID id : chosen) {
                want.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
                used += partsOf.getOrDefault(id, 0);
            }
            ourParts.put(pid, used);
        }
        for (Map.Entry<UUID, List<Player>> e : want.entrySet()) {
            FishOut o = f.get(e.getKey());
            if (o == null) {
                continue;
            }
            Shown s = shown.computeIfAbsent(e.getKey(), k -> new Shown());
            Set<UUID> ids = new HashSet<>();
            e.getValue().forEach(p -> ids.add(p.getUniqueId()));
            s.hiddenSince = -1;
            RigInstance rig = s.rig;
            if (rig == null || !rig.isValid()) {
                spawnRig(s, o, e.getValue(), bal);
            } else if (!ids.equals(s.viewers)) {
                rig.setViewers(e.getValue());
                s.viewers.clear();
                s.viewers.addAll(ids);
            }
        }
        int graceTicks = bal.hiddenGraceSeconds() * 20;
        Iterator<Map.Entry<UUID, Shown>> it = shown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Shown> e = it.next();
            if (want.containsKey(e.getKey())) {
                continue;
            }
            Shown s = e.getValue();
            if (f.get(e.getKey()) == null) {
                removeRig(s);
                it.remove();
                continue;
            }
            RigInstance rig = s.rig;
            if (!s.viewers.isEmpty()) {
                if (rig != null && rig.isValid()) {
                    rig.setViewers(List.of());
                }
                s.viewers.clear();
                s.hiddenSince = tick;
            } else if (s.hiddenSince < 0 || tick - s.hiddenSince > graceTicks) {
                removeRig(s);
                it.remove();
            }
        }
    }

    /**
     * Parts used by other modules' rigs out of Core's 300 (visibleParts − our share). Recounted
     * every few seconds.
     */
    private void refreshOtherParts(WildlifeBalance bal, long tick) {
        if (tick - lastOtherParts < bal.otherPartsRefreshSeconds() * 20L) {
            return;
        }
        lastOtherParts = tick;
        otherParts.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int all = kit.api.rigs().visibleParts(p);
            otherParts.put(
                    p.getUniqueId(), Math.max(0, all - ourParts.getOrDefault(p.getUniqueId(), 0)));
        }
    }

    /** Effects intensity (Fishing setting '/낚시 설정', 0 minimum, 1 normal, 2 maximum). */
    private int intensity(UUID player) {
        return kit.api
                .flags()
                .playerValue(player, INTENSITY_FLAG)
                .map(
                        v -> {
                            try {
                                return Math.clamp(Integer.parseInt(v), 0, 2);
                            } catch (NumberFormatException e) {
                                return 2;
                            }
                        })
                .orElse(2);
    }

    private void spawnRig(Shown s, FishOut o, List<Player> viewers, WildlifeBalance bal) {
        World w = Bukkit.getWorld(o.world());
        if (w == null || o.hidden()) {
            return;
        }
        SpeciesProfile p = o.profile();
        Location loc = new Location(w, o.x(), o.y(), o.z(), o.yaw(), o.pitch());
        RigOptions options =
                RigOptions.privateTo(viewers)
                        .withAnimation(o.animation().isEmpty() ? null : o.animation())
                        .withScale(p.scale())
                        .withMoveTicks(bal.nearMoveTicks())
                        .withPhase(0);
        if (p.light() >= 0) {
            options = options.withBrightness(Math.min(15, p.light()), Math.min(15, p.light()));
        }
        RigInstance rig;
        try {
            rig = kit.api.rigs().spawn(p.rig(), loc, options);
        } catch (IllegalArgumentException e) {
            kit.log.warn("야생 개체 리그를 띄우지 못함 {}: {}", p.rig(), String.valueOf(e.getMessage()));
            return;
        }
        UUID id = o.id();
        rig.root()
                .getPersistentDataContainer()
                .set(WildlifeKit.WILD_KEY, PersistentDataType.STRING, id.toString());
        rootIds.add(rig.root().getUniqueId());
        // resync: the prototype restarted from frame 0, but we compute the current frame from the
        // fish's phase and continue from there
        int fps =
                Math.max(
                        1,
                        kit.api.content().snapshot().single(Balance.TYPE).integer("rig.fps", 10));
        int ticksPerFrame = Math.max(1, 20 / fps);
        int frames = rig.frameCount();
        if (frames > 0) {
            rig.setPhase(
                    Math.floorMod(
                            (Bukkit.getCurrentTick() + (long) o.phaseOffset()) / ticksPerFrame,
                            frames));
        }
        rig.setSpeed(o.animationSpeed());
        rig.onRemove(
                reason -> {
                    rootIds.remove(rig.root().getUniqueId());
                    Shown cur = shown.get(id);
                    if (cur != null && sameRig(cur.rig, rig)) {
                        cur.rig = null;
                    }
                    if (reason == RigInstance.RemoveReason.CHUNK_UNLOAD
                            || reason == RigInstance.RemoveReason.ENTITY_GONE) {
                        engine.submit(world -> world.remove(id, SimEvent.Reason.CHUNK_UNLOAD));
                    }
                });
        s.rig = rig;
        s.viewers.clear();
        viewers.forEach(v -> s.viewers.add(v.getUniqueId()));
        s.oneShotSeq = o.oneShotSeq();
        s.teleportSeq = o.teleportSeq();
        s.yaw = o.yaw();
        s.pitch = o.pitch();
    }

    private static boolean sameRig(@Nullable RigInstance a, RigInstance b) {
        return a != null && a.id().equals(b.id());
    }

    private void removeRig(Shown s) {
        RigInstance rig = s.rig;
        s.rig = null;
        s.viewers.clear();
        if (rig != null && rig.isValid()) {
            rootIds.remove(rig.root().getUniqueId());
            rig.remove();
        }
    }

    private void forgetRig(Shown s) {
        s.rig = null;
        s.viewers.clear();
    }

    /** Clears display state and pending applies when a fish disappears. */
    void destroy(UUID id) {
        Shown s = shown.remove(id);
        if (s != null) {
            removeRig(s);
        }
        applyQueue.remove(id);
    }

    /**
     * Takes down only that fish's rig (display state is kept so the next selection shows it again).
     */
    void removeRigOf(UUID id) {
        Shown s = shown.get(id);
        if (s != null) {
            removeRig(s);
        }
    }

    /** Whether it has a rig and at least one viewer (WildFish.visible). */
    boolean displayed(UUID id) {
        Shown s = shown.get(id);
        return s != null && s.rig != null && !s.viewers.isEmpty();
    }

    // ---------------------------------------------------------------- viewers

    /**
     * Removes the player from viewer lists. Fish left with no viewers are hidden at the next
     * selection and drop to 2Hz.
     */
    void dropViewer(Player p, boolean online, long tick) {
        UUID pid = p.getUniqueId();
        for (Shown s : shown.values()) {
            if (s.viewers.remove(pid)) {
                RigInstance rig = s.rig;
                if (online && rig != null && rig.isValid()) {
                    rig.removeViewer(p);
                }
                if (s.viewers.isEmpty()) {
                    s.hiddenSince = tick;
                }
            }
        }
    }

    /** Clears the part tally of a player who left. */
    void forgetPlayer(UUID id) {
        otherParts.remove(id);
        ourParts.remove(id);
    }

    // ---------------------------------------------------------------- cleanup

    /**
     * Removes orphaned harbor:wild display entities on startup (same role as the prototype's
     * orphaned part cleanup).
     */
    void removeOrphans() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(WildlifeKit.WILD_KEY)
                        && !rootIds.contains(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
    }

    /** Removes orphaned harbor:wild entities among those that came in with a chunk load. */
    void removeOrphans(List<Entity> entities) {
        for (Entity e : entities) {
            if (e.getPersistentDataContainer().has(WildlifeKit.WILD_KEY)
                    && !rootIds.contains(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** Number of fish currently shown with rigs, and their part count. */
    int[] shownStats() {
        int rigs = 0;
        for (Shown s : shown.values()) {
            if (s.rig != null) {
                rigs++;
            }
        }
        return new int[] {shown.size(), rigs};
    }

    int ourParts(UUID player) {
        return ourParts.getOrDefault(player, 0);
    }

    int otherParts(UUID player) {
        return otherParts.getOrDefault(player, 0);
    }

    boolean visibleTo(UUID fish, UUID player) {
        Shown s = shown.get(fish);
        return s != null && s.viewers.contains(player);
    }

    int degradeLevel() {
        return degrade;
    }

    double lastApplyMillis() {
        return lastApplyNanos / 1e6;
    }

    int deferred() {
        return lastDeferred;
    }

    /** This player's current budget (fish count, parts). */
    int[] budgetOf(Player p) {
        WildlifeBalance bal = kit.balance();
        int count =
                ViewBudget.countBudget(
                        bal.maxShownPerPlayer(), degrade >= 2 ? bal.lagShownScale() : 1.0);
        int parts =
                ViewBudget.partBudget(
                        bal.maxPartsPerPlayer(),
                        bal.intensityScale(intensity(p.getUniqueId())),
                        bal.corePartBudget(),
                        otherParts.getOrDefault(p.getUniqueId(), 0));
        return new int[] {count, parts};
    }
}
