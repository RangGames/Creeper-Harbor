package wiki.creeper.harbor.wildlife.runtime;

import io.papermc.paper.world.MoonPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
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
import wiki.creeper.harbor.wildlife.sim.MediumField;
import wiki.creeper.harbor.wildlife.sim.PlayerSnap;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.wildlife.sim.Raycast;
import wiki.creeper.harbor.wildlife.sim.SimEvent;
import wiki.creeper.harbor.wildlife.sim.SpawnOrder;
import wiki.creeper.harbor.wildlife.sim.SpawnPlanner;
import wiki.creeper.harbor.wildlife.sim.SpawnTable;
import wiki.creeper.harbor.wildlife.sim.SpeciesProfile;
import wiki.creeper.harbor.wildlife.sim.Steer;
import wiki.creeper.harbor.wildlife.sim.ViewBudget;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.RegionInfo;

/**
 * 메인 스레드 쪽 야생 개체 관리 ('17-3' 9.4). 계산 스레드({@link SimEngine})에 플레이어 상태를 넘기고, 출력 면을 받아 리그에 반영한다.
 *
 * <ul>
 *   <li>스레드 1단계: 틱 끝에 플레이어 스냅샷(위치·웅크림·보트)을 복사해 넘긴다
 *   <li>스레드 3단계: 숨긴 실제 엔티티 방식이라 메인 스레드가 틱마다 출력을 반영하되 1.0ms 예산을 넘으면 다음 틱으로 넘긴다 (차례 돌림). 반영이 2주기 넘게
 *       밀리면 16~40블록 전송을 4틱 → 6틱으로, 그래도 밀리면 표시 상한을 줄인다 (자동 감속)
 *   <li>표시 대상 선정: 1초마다 플레이어별로 40블록 안 개체를 거리순으로 예산(마릿수·부품)까지 채운다. 부품 예산은 연출 강도 50·75·100%와 Core 기준
 *       300개 중 다른 리그(낚시 그림자·수족관·보스)가 쓰고 남은 몫 중 작은 쪽
 *   <li>자연 스폰: 플레이어당 목표 12마리, 초당 무리 2개, 서버 틱당 20마리, 전체 2,000마리. 물고기 떼 포인트에는 무리를 하나 더 둔다
 *   <li>정리: 리그 엔티티는 저장하지 않고 루트에 harbor:wild를 단다. 시작·청크 적재 때 주인 잃은 것을 지운다
 * </ul>
 */
public final class WildlifeManager implements WildlifeService, Listener {

    private static final String INTENSITY_FLAG = "fishing/settings/intensity";

    /** 보이는(또는 잠깐 숨긴) 개체의 표시 상태. */
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

    private record Recent(String world, Vec3 pos, int count, long seq, @Nullable String tag) {}

    private record Boost(
            String source,
            @Nullable RegionId region,
            Set<FishId> fish,
            double mult,
            Instant until) {}

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final Set<UUID> rootIds = new HashSet<>();
    private final Set<UUID> applyQueue = new LinkedHashSet<>();
    private final Map<UUID, Integer> ourParts = new HashMap<>();
    private final Map<UUID, Integer> otherParts = new HashMap<>();
    private final Map<UUID, Integer> credits = new HashMap<>();
    private final Map<UUID, Long> turnedBack = new HashMap<>();
    private final List<Recent> recent = new ArrayList<>();
    private final Map<String, Boost> boosts = new HashMap<>();
    private final Map<String, FishingSpot> anchors = new HashMap<>();
    private Frame applied = Frame.EMPTY;
    private long tick;
    private int lagCycles;
    private int degrade;
    private long calmSince;
    private long lastOtherParts;
    private int regionCursor;
    private int spawnCursor;
    private long lastApplyNanos;
    private int lastDeferred;
    private @Nullable DebugOverlay debug;

    WildlifeManager(WildlifeKit kit, SimEngine engine) {
        this.kit = kit;
        this.engine = engine;
    }

    void debug(DebugOverlay overlay) {
        this.debug = overlay;
    }

    void start() {
        removeOrphans();
        for (RegionInfo r : kit.regions.regions()) {
            for (FishingSpot s : kit.spots.spots(r.id())) {
                if (!s.hidden()) {
                    anchors.put(s.id(), s);
                }
            }
        }
        for (ActiveSeaEvent e : kit.seaEvents.active()) {
            seaEventStarted(e);
        }
        var _ = kit.tasks.timer(1, 1, this::tick);
    }

    void shutdown() {
        for (Shown s : shown.values()) {
            removeRig(s);
        }
        shown.clear();
        rootIds.clear();
    }

    Frame frame() {
        return applied;
    }

    SimEngine engine() {
        return engine;
    }

    // ================================================================== 틱

    private void tick() {
        tick++;
        WildlifeBalance bal = kit.balance();
        snapshotPlayers();
        pullFrame(bal);
        applyPending(bal);
        if (tick % 20 == 0) {
            viewerPass(bal);
            refillCredits(bal);
            spotPass(bal);
            prune();
        }
        spawnPass(bal);
        regionCheck();
    }

    /** 스레드 1단계: 플레이어 상태 복사. */
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

    // ---------------------------------------------------------------- 출력 반영

    private void pullFrame(WildlifeBalance bal) {
        Frame f = engine.latest();
        if (f.seq() == applied.seq()) {
            return;
        }
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
        applied = f;
        for (SimEvent e : f.events()) {
            switch (e) {
                case SimEvent.Removed r -> destroy(r.fish());
                case SimEvent.Startle s -> startled(f, s);
                case SimEvent.Particle p -> particle(p, bal);
            }
        }
        for (Map.Entry<UUID, Shown> e : shown.entrySet()) {
            if (e.getValue().rig != null) {
                applyQueue.add(e.getKey());
            }
        }
    }

    private void applyPending(WildlifeBalance bal) {
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
            if (applied.seq() % every == 0) {
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

    /** 동작 파티클: 16블록 이하 플레이어에게만 ('17-3' 9.4 LOD 16~40블록은 생략). 용암에서는 물방울 대신 연기·용암 파티클. */
    private void particle(SimEvent.Particle p, WildlifeBalance bal) {
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

    // ---------------------------------------------------------------- 표시 대상

    private void viewerPass(WildlifeBalance bal) {
        Frame f = applied;
        refreshOtherParts(bal);
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

    /** Core 기준 300개 중 다른 모듈 리그가 쓰는 부품 (visibleParts − 우리 몫). 몇 초마다 다시 센다. */
    private void refreshOtherParts(WildlifeBalance bal) {
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

    /** 연출 강도 (Fishing 설정 '/낚시 설정', 0 최소 · 1 보통 · 2 최대). */
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
        // 재동기화: 시제품은 0번 프레임부터 다시 재생했지만, 개체 위상에서 지금 프레임을 계산해 그 자리부터 잇는다
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

    private void destroy(UUID id) {
        Shown s = shown.remove(id);
        if (s != null) {
            removeRig(s);
        }
        applyQueue.remove(id);
        turnedBack.remove(id);
        DebugOverlay d = debug;
        if (d != null) {
            d.forget(id);
        }
    }

    /** 지금 리그로 보이는 개체 수와 부품 수. */
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

    /** 이 플레이어의 지금 예산 (마릿수, 부품). */
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

    // ---------------------------------------------------------------- 자연 스폰

    private void refillCredits(WildlifeBalance bal) {
        credits.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            credits.put(p.getUniqueId(), bal.schoolsPerPlayerPerSecond());
        }
    }

    private void prune() {
        long seq = applied.seq();
        recent.removeIf(r -> r.seq() < seq - 2);
        Instant now = Instant.now();
        boosts.values().removeIf(b -> b.until().isBefore(now));
        turnedBack.values().removeIf(t -> tick - t > 200);
    }

    private int recentCount() {
        int n = 0;
        for (Recent r : recent) {
            n += r.count();
        }
        return n;
    }

    private void spawnPass(WildlifeBalance bal) {
        List<? extends Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        int total = applied.fish().size() + recentCount();
        int fishBudget = bal.spawnPerTick();
        ProfileIndex index = kit.index();
        int n = players.size();
        for (int i = 0; i < n && fishBudget > 0 && total < bal.globalMax(); i++) {
            Player p = players.get((spawnCursor + i) % n);
            UUID pid = p.getUniqueId();
            int credit = credits.getOrDefault(pid, 0);
            if (credit <= 0) {
                continue;
            }
            Optional<RegionId> region = kit.regions.current(pid);
            if (region.isEmpty() || index.region(region.get()).isEmpty()) {
                continue;
            }
            double target = bal.targetPerPlayer() * boostTarget(region.get());
            Location l = Locs.of(p);
            int around =
                    applied.nearby(
                                    p.getWorld().getName(),
                                    l.getX(),
                                    l.getY(),
                                    l.getZ(),
                                    bal.showDistance())
                            .size();
            for (Recent r : recent) {
                if (r.world().equals(p.getWorld().getName())
                        && Steer.distanceSq(r.pos(), new Vec3(l.getX(), l.getY(), l.getZ()))
                                <= bal.showDistance() * bal.showDistance()) {
                    around += r.count();
                }
            }
            if (around >= target) {
                continue;
            }
            credits.put(pid, credit - 1);
            int made = attemptNatural(p, region.get(), bal, index);
            fishBudget -= made;
            total += made;
        }
        spawnCursor = (spawnCursor + 1) % Math.max(1, n);
    }

    private int attemptNatural(
            Player player, RegionId region, WildlifeBalance bal, ProfileIndex index) {
        List<Medium> mediums = new ArrayList<>(index.mediums(region));
        if (mediums.isEmpty()) {
            return 0;
        }
        Medium medium = mediums.get(random.nextInt(mediums.size()));
        World w = player.getWorld();
        MediumField field = kit.grid.field(w.getName());
        Location l = Locs.of(player);
        for (int attempt = 0; attempt < 3; attempt++) {
            double[] xz =
                    SpawnPlanner.ringPoint(
                            l.getX(), l.getZ(), bal.spawnRingMin(), bal.spawnRingMax(), random);
            int x = MediumField.floor(xz[0]);
            int z = MediumField.floor(xz[1]);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int top;
            int bottom;
            if (medium == Medium.AIR) {
                double base = bal.airBaseY(region.id().name());
                top = (int) Math.floor(base + bal.airBandAbove());
                bottom = (int) Math.ceil(base - bal.airBandBelow());
            } else {
                top = l.getBlockY() + 12;
                bottom = l.getBlockY() - 28;
            }
            top = Math.min(top, w.getMaxHeight() - 1);
            bottom = Math.max(bottom, w.getMinHeight());
            Optional<SpawnPlanner.Spot> spot =
                    SpawnPlanner.column(
                            field,
                            medium,
                            x,
                            z,
                            top,
                            bottom,
                            bal.minLiquidDepth(),
                            bal.airClearance(),
                            random);
            if (spot.isEmpty()) {
                continue;
            }
            Vec3 pos = spot.get().pos();
            if (medium == Medium.FLOOR && !floorOk(w, spot.get(), bal)) {
                continue;
            }
            if (!SpawnPlanner.farFrom(pos, schoolCenters(w.getName(), pos, bal), bal.schoolGap())) {
                continue;
            }
            Location at = new Location(w, pos.x(), pos.y(), pos.z());
            Optional<RegionKeyResolver.RegionKey> key = kit.regions.fishingKey(at);
            if (key.isEmpty()) {
                continue;
            }
            RegionId keyRegion = key.get().region();
            SpawnTable.Conditions c =
                    new SpawnTable.Conditions(
                            keyRegion,
                            SpawnTable.timeKey(w.getTime()),
                            weather(w),
                            moon(w),
                            key.get().areas(),
                            flag -> kit.api.flags().hasServer(flag),
                            Set.of(medium),
                            spot.get().depth());
            Optional<SpeciesProfile> pick =
                    SpawnTable.pick(
                            index.region(keyRegion),
                            c,
                            bal::gradeWeight,
                            f -> boostWeight(keyRegion, f),
                            random);
            if (pick.isEmpty()) {
                continue;
            }
            SpeciesProfile p = pick.get();
            int size = SpawnPlanner.schoolSize(p.schoolMin(), p.schoolMax(), random);
            return order(
                            p,
                            at,
                            size,
                            null,
                            WildFishSpawnEvent.Reason.NATURAL,
                            null,
                            keyRegion,
                            List.of())
                    .size();
        }
        return 0;
    }

    /** 바닥 개체: 해저 모래·자갈 위, 청크당 2개 이하. */
    private boolean floorOk(World w, SpawnPlanner.Spot spot, WildlifeBalance bal) {
        int x = MediumField.floor(spot.pos().x());
        int z = MediumField.floor(spot.pos().z());
        Material below = w.getBlockAt(x, spot.floorY() - 1, z).getType();
        Set<String> allowed = Set.of(bal.floorBlocks().split(","));
        if (!allowed.contains(below.getKey().getKey())) {
            return false;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        int count = 0;
        for (FishOut o :
                applied.nearby(w.getName(), cx * 16 + 8, spot.pos().y(), cz * 16 + 8, 12)) {
            if (o.profile().medium() == Medium.FLOOR
                    && MediumField.floor(o.x()) >> 4 == cx
                    && MediumField.floor(o.z()) >> 4 == cz) {
                count++;
            }
        }
        return count < bal.floorPerChunk();
    }

    private List<Vec3> schoolCenters(String world, Vec3 around, WildlifeBalance bal) {
        List<Vec3> centers = new ArrayList<>();
        for (FishOut o :
                applied.nearby(world, around.x(), around.y(), around.z(), bal.schoolGap() + 2)) {
            if (o.school() == null || o.leader()) {
                centers.add(new Vec3(o.x(), o.y(), o.z()));
            }
        }
        for (Recent r : recent) {
            if (r.world().equals(world)) {
                centers.add(r.pos());
            }
        }
        return centers;
    }

    private static String weather(World w) {
        return w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear";
    }

    private static String moon(World w) {
        MoonPhase phase = w.getMoonPhase();
        return phase == MoonPhase.FULL_MOON ? "full" : phase == MoonPhase.NEW_MOON ? "new" : "";
    }

    /**
     * 생성 주문을 낸다 (WildFishSpawnEvent 취소 시 빈 목록).
     *
     * @return 만든 개체 ID (첫 번째가 대장)
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
        World w = at.getWorld();
        if (w == null || count <= 0) {
            return List.of();
        }
        WildFishSpawnEvent event =
                new WildFishSpawnEvent(p.fish().id(), at, count, p.medium(), reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        UUID school = count > 1 ? UUID.randomUUID() : null;
        RegionId home = region != null ? region : p.fish().region();
        Vec3 center = new Vec3(at.getX(), at.getY(), at.getZ());
        SpawnOrder o =
                new SpawnOrder(
                        ids,
                        p,
                        w.getName(),
                        center,
                        school,
                        scripted,
                        path,
                        tag,
                        reason == WildFishSpawnEvent.Reason.SCRIPTED
                                        || reason == WildFishSpawnEvent.Reason.PATH
                                ? null
                                : home.toString(),
                        kit.balance().airBaseY(home.id().name()));
        engine.submit(world -> world.spawn(o));
        recent.add(new Recent(w.getName(), center, count, applied.seq(), tag));
        return ids;
    }

    // ---------------------------------------------------------------- 물고기 떼·해상 이벤트

    void spotMoved(FishingSpot spot, @Nullable FishingSpot previous) {
        if (previous != null) {
            anchors.remove(previous.id());
        }
        if (!spot.hidden()) {
            anchors.put(spot.id(), spot);
        }
    }

    /** 물고기 떼 자리에 그 지역 어종 무리를 하나 더 둔다 (가까이 플레이어가 있을 때만). */
    private void spotPass(WildlifeBalance bal) {
        if (anchors.isEmpty()) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (FishOut o : applied.fish()) {
            String tag = o.tag();
            if (tag != null) {
                present.add(tag);
            }
        }
        for (Recent r : recent) {
            String tag = r.tag();
            if (tag != null) {
                present.add(tag);
            }
        }
        ProfileIndex index = kit.index();
        for (FishingSpot spot : anchors.values()) {
            if (present.contains(spot.id()) || index.region(spot.region()).isEmpty()) {
                continue;
            }
            World w = actualWorld(spot.world());
            if (w == null
                    || !w.isChunkLoaded(
                            MediumField.floor(spot.x()) >> 4, MediumField.floor(spot.z()) >> 4)) {
                continue;
            }
            Location center = new Location(w, spot.x(), spot.y(), spot.z());
            double show2 = bal.showDistance() * bal.showDistance();
            boolean watched = false;
            for (Player p : w.getPlayers()) {
                if (Locs.of(p).distanceSquared(center) <= show2) {
                    watched = true;
                    break;
                }
            }
            if (!watched) {
                continue;
            }
            int x = MediumField.floor(spot.x());
            int z = MediumField.floor(spot.z());
            Optional<SpawnPlanner.Spot> found =
                    SpawnPlanner.column(
                            kit.grid.field(w.getName()),
                            Medium.WATER,
                            x,
                            z,
                            (int) spot.y() + 4,
                            (int) spot.y() - 20,
                            bal.minLiquidDepth(),
                            bal.airClearance(),
                            random);
            if (found.isEmpty()) {
                continue;
            }
            Vec3 pos = found.get().pos();
            Location at = new Location(w, pos.x(), pos.y(), pos.z());
            SpawnTable.Conditions c =
                    new SpawnTable.Conditions(
                            spot.region(),
                            SpawnTable.timeKey(w.getTime()),
                            weather(w),
                            moon(w),
                            kit.regions.areasAt(at),
                            flag -> kit.api.flags().hasServer(flag),
                            Set.of(Medium.WATER),
                            found.get().depth());
            Optional<SpeciesProfile> pick =
                    SpawnTable.pick(
                            index.region(spot.region()),
                            c,
                            bal::gradeWeight,
                            f -> boostWeight(spot.region(), f),
                            random);
            pick.ifPresent(
                    p ->
                            order(
                                    p,
                                    at,
                                    Math.max(p.schoolMax(), p.schoolMin()),
                                    null,
                                    WildFishSpawnEvent.Reason.SPOT,
                                    spot.id(),
                                    spot.region(),
                                    List.of()));
        }
    }

    private @Nullable World actualWorld(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return direct;
        }
        for (World w : Bukkit.getWorlds()) {
            if (kit.contentWorld(w).equals(contentWorld)) {
                return w;
            }
        }
        return null;
    }

    /** 물고기 대이동: 대상 어종 밀도 ×wildlifeBoost (World가 정한 배율, 없으면 balance 기본 ×2). */
    void seaEventStarted(ActiveSeaEvent e) {
        if (e.phase() != ActiveSeaEvent.Phase.ACTIVE || e.wildlifeBoost() <= 1) {
            return;
        }
        Set<FishId> fish = new HashSet<>();
        e.fishEffects().stream()
                .filter(fx -> fx.type().equals("species_weight"))
                .forEach(
                        fx -> {
                            try {
                                fish.add(FishId.parse(fx.fish()));
                            } catch (IllegalArgumentException ignored) {
                                // 잘못된 어종 ID는 World 콘텐츠 검사가 잡는다
                            }
                        });
        boosts.put(
                "event:" + e.runId(),
                new Boost(
                        "event:" + e.runId(),
                        e.region(),
                        Set.copyOf(fish),
                        e.wildlifeBoost(),
                        e.endsAt()));
    }

    void seaEventEnded(ActiveSeaEvent e) {
        boosts.remove("event:" + e.runId());
    }

    private double boostTarget(RegionId region) {
        double m = 1;
        Instant now = Instant.now();
        for (Boost b : boosts.values()) {
            if (b.until().isAfter(now) && (b.region() == null || b.region().equals(region))) {
                m = Math.max(m, b.mult());
            }
        }
        return m;
    }

    private double boostWeight(RegionId region, FishId fish) {
        double m = 1;
        Instant now = Instant.now();
        for (Boost b : boosts.values()) {
            if (b.until().isAfter(now)
                    && (b.region() == null || b.region().equals(region))
                    && (b.fish().isEmpty() || b.fish().contains(fish))) {
                m = Math.max(m, b.mult());
            }
        }
        return m;
    }

    int boostCount() {
        return boosts.size();
    }

    int anchorCount() {
        return anchors.size();
    }

    // ---------------------------------------------------------------- 지역 경계

    /** 지역 경계를 넘은 개체는 방향을 돌린다 (틱당 일부만 차례로 확인). */
    private void regionCheck() {
        List<FishOut> list = applied.fish();
        int size = list.size();
        if (size == 0) {
            return;
        }
        int n = Math.min(100, size);
        for (int i = 0; i < n; i++) {
            FishOut o = list.get((regionCursor + i) % size);
            String home = o.region();
            if (home == null || o.hidden()) {
                continue;
            }
            Long last = turnedBack.get(o.id());
            if (last != null && tick - last < 40) {
                continue;
            }
            World w = Bukkit.getWorld(o.world());
            if (w == null) {
                continue;
            }
            Optional<RegionInfo> r = kit.regions.regionAt(new Location(w, o.x(), o.y(), o.z()));
            if (r.isEmpty() || !r.get().id().toString().equals(home)) {
                turnedBack.put(o.id(), tick);
                UUID id = o.id();
                engine.submit(world -> world.turnBack(id));
            }
        }
        regionCursor = (regionCursor + n) % size;
    }

    // ---------------------------------------------------------------- 정리

    /** 시작 시 주인 잃은 harbor:wild 표시 엔티티를 지운다 (시제품의 주인 잃은 부품 정리와 같은 역할). */
    private void removeOrphans() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(WildlifeKit.WILD_KEY)
                        && !rootIds.contains(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e.getPersistentDataContainer().has(WildlifeKit.WILD_KEY)
                    && !rootIds.contains(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        dropViewer(event.getPlayer(), false);
        UUID id = event.getPlayer().getUniqueId();
        credits.remove(id);
        otherParts.remove(id);
        ourParts.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        dropViewer(event.getPlayer(), true);
    }

    /** 그 플레이어를 시청자 목록에서 뺀다. 남은 시청자가 없는 개체는 다음 선정 때 숨기고 2Hz로 내린다. */
    private void dropViewer(Player p, boolean online) {
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

    /** 콘텐츠 리로드: 리그가 바뀐 어종만 즉시 다시 만든다. 나머지는 다음 정리 때 자연스럽게 교체된다. */
    void reloaded(ProfileIndex before, ProfileIndex after) {
        for (SpeciesProfile next : after.bySpecies().values()) {
            SpeciesProfile prev = before.bySpecies().get(next.species());
            if (prev != null && !prev.rig().equals(next.rig())) {
                engine.submit(world -> world.replaceProfile(next.species(), next));
                for (FishOut o : applied.fish()) {
                    if (o.profile().species().equals(next.species())) {
                        Shown s = shown.get(o.id());
                        if (s != null) {
                            removeRig(s);
                        }
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
        Shown s = shown.get(o.id());
        return new WildFish(
                o.id(),
                o.profile().fish().id(),
                o.profile().species(),
                new Location(w, o.x(), o.y(), o.z(), o.yaw(), o.pitch()),
                o.state(),
                o.profile().medium(),
                o.school(),
                o.profile().radius(),
                s != null && s.rig != null && !s.viewers.isEmpty(),
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

    /** ShadowDriver.isValid용: 마지막 면에 살아 있는지. */
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

    /** 위치가 매질 밖이면 위아래 3칸 안에서 매질 칸을 찾아 띄운다. */
    List<UUID> spawnAt(
            SpeciesProfile p,
            Location at,
            int count,
            @Nullable ScriptedBehaviour behaviour,
            WildFishSpawnEvent.Reason reason,
            List<Vec3> path) {
        World w = at.getWorld();
        if (w == null) {
            return List.of();
        }
        MediumField field = kit.grid.field(w.getName());
        Medium cell = p.medium().cell();
        Location spot = null;
        for (int dy : new int[] {0, -1, 1, -2, 2, -3, 3}) {
            Location c = at.clone().add(0, dy, 0);
            if (field.is(cell, c.getX(), c.getY(), c.getZ())) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            return List.of();
        }
        RegionId region = kit.regions.regionAt(spot).map(RegionInfo::id).orElse(null);
        return order(p, spot, count, behaviour, reason, null, region, path);
    }

    @Override
    public Optional<UUID> spawnSchool(FishId fish, List<Location> path, int count) {
        SpeciesProfile p = kit.index().byFish().get(fish);
        if (p == null || path.isEmpty() || p.medium() == Medium.FLOOR) {
            return Optional.empty();
        }
        List<Vec3> points = new ArrayList<>();
        for (Location l : path.subList(1, path.size())) {
            points.add(new Vec3(l.getX(), l.getY(), l.getZ()));
        }
        int n = count > 0 ? count : SpawnPlanner.schoolSize(p.schoolMin(), p.schoolMax(), random);
        List<UUID> ids =
                spawnAt(p, path.getFirst(), n, null, WildFishSpawnEvent.Reason.PATH, points);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    @Override
    public void boost(
            String source, RegionId region, Set<FishId> fish, double multiplier, Instant until) {
        boosts.put(source, new Boost(source, region, Set.copyOf(fish), multiplier, until));
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
