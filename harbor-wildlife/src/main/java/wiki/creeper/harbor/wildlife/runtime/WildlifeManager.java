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
 *
 * <p>틱 순서와 마지막 면·틱 수만 여기서 들고, 리그 표시는 {@link RigPresenter}, 생성은 {@link WildSpawner}, 밀도 배율은 {@link
 * SpawnBoosts}, 지역 경계는 {@link RegionFence}가 맡는다.
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

    // ================================================================== 틱

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

    // ---------------------------------------------------------------- 진단
    // (WildCommands·DebugOverlay)

    /** 지금 리그로 보이는 개체 수와 부품 수. */
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

    /** 이 플레이어의 지금 예산 (마릿수, 부품). */
    int[] budgetOf(Player p) {
        return rigs.budgetOf(p);
    }

    int boostCount() {
        return boosts.count();
    }

    int anchorCount() {
        return spawner.anchorCount();
    }

    // ---------------------------------------------------------------- 생성 (WildCommands)

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
        return spawner.order(p, at, count, scripted, reason, tag, region, path);
    }

    /** 위치가 매질 밖이면 위아래 3칸 안에서 매질 칸을 찾아 띄운다. */
    List<UUID> spawnAt(
            SpeciesProfile p,
            Location at,
            int count,
            @Nullable ScriptedBehaviour behaviour,
            WildFishSpawnEvent.Reason reason,
            List<Vec3> path) {
        return spawner.spawnAt(p, at, count, behaviour, reason, path);
    }

    // ---------------------------------------------------------------- 물고기 떼·해상 이벤트 (WorldLink)

    void spotMoved(FishingSpot spot, @Nullable FishingSpot previous) {
        spawner.spotMoved(spot, previous);
    }

    /** 물고기 대이동: 대상 어종 밀도 ×wildlifeBoost (World가 정한 배율, 없으면 balance 기본 ×2). */
    void seaEventStarted(ActiveSeaEvent e) {
        boosts.seaEventStarted(e);
    }

    void seaEventEnded(ActiveSeaEvent e) {
        boosts.seaEventEnded(e);
    }

    // ---------------------------------------------------------------- 정리

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

    /** 콘텐츠 리로드: 리그가 바뀐 어종만 즉시 다시 만든다. 나머지는 다음 정리 때 자연스럽게 교체된다. */
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
