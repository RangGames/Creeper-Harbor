package wiki.creeper.harbor.world.region;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.RegionInfo;
import wiki.creeper.harbor.world.api.RegionService;
import wiki.creeper.harbor.world.api.UnlockStatus;
import wiki.creeper.harbor.world.api.event.RegionEnterEvent;
import wiki.creeper.harbor.world.api.event.RegionFirstVisitEvent;
import wiki.creeper.harbor.world.api.event.RegionLeaveEvent;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.data.WorldRepository;
import wiki.creeper.harbor.world.data.WorldState;

/**
 * 지역 판정·진입 조건·밀어내기·첫 방문·체류 인원 (docs 20 8.4 '지역 판정' · '진입 조건' · '밀어내기'). Fishing의 RegionKeyResolver도
 * 구현한다 (지역, 찌 자리 바이옴 키, 세부 구역, 물고기 떼). 메인 스레드 전용.
 */
public final class RegionManager implements Listener, RegionService, RegionKeyResolver {

    public static final PlayerComponent<WorldState> COMPONENT =
            new PlayerComponent<>("world", WorldRepository::load, WorldRepository::save);

    public static final String BYPASS = "harbor.world.bypass";

    /** 다른 매니저에 넘기는 연결 지점 (생성 순서를 풀기 위해 나중에 붙인다). */
    public interface Hooks {
        /** 지역 안에서 청크가 바뀌었을 때 (해도). */
        void onChunk(Player player, WorldIndex.Entry region, int cx, int cz);

        /** 타고 있는 선박 종류 (없으면 null). */
        @Nullable HarborId shipType(Player player);

        /** 찌 자리의 물고기 떼 (보정자 등록 포함). */
        Optional<FishingSpot> school(@Nullable Player shooter, Location bobber);
    }

    static final class Track {
        @Nullable RegionId region;
        List<String> areas = List.of();
        long chunk = Long.MIN_VALUE;
        @Nullable Location safe;
        Instant dialogAt = Instant.EPOCH;
    }

    private final WorldKit kit;
    private final ServerRouter router;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final RegionBarrier barrier;
    private final RegionOccupancy occupancyBoard;
    private @Nullable Hooks hooks;

    public RegionManager(WorldKit kit, ServerRouter router) {
        this.kit = kit;
        this.router = router;
        this.barrier = new RegionBarrier(kit, router, this);
        this.occupancyBoard = new RegionOccupancy(kit, this);
    }

    /**
     * Entry-condition listener (push-back, teleport/portal guards); register it alongside this
     * manager.
     */
    public RegionBarrier barrier() {
        return barrier;
    }

    public void hooks(Hooks value) {
        this.hooks = value;
    }

    public void start() {
        var _ = kit.tasks().timer(20, 20, this::saveSafePositions);
        long publish = 20L * kit.balance().occupancyPublishSeconds();
        var _ = kit.tasks().timer(publish, publish, occupancyBoard::publish);
    }

    // ------------------------------------------------------------------ 조회

    public Optional<WorldIndex.Entry> entryAt(Location location) {
        return kit.index().at(kit.worldId(location), location.getX(), location.getZ());
    }

    @Override
    public List<RegionInfo> regions() {
        return kit.index().regions().stream().map(WorldIndex.Entry::info).toList();
    }

    @Override
    public Optional<RegionInfo> region(RegionId id) {
        return kit.index().get(id).map(WorldIndex.Entry::info);
    }

    @Override
    public Optional<RegionInfo> regionAt(Location location) {
        return entryAt(location).map(WorldIndex.Entry::info);
    }

    @Override
    public List<String> areasAt(Location location) {
        return entryAt(location)
                .map(e -> e.areasAt(location.getX(), location.getY(), location.getZ()))
                .orElse(List.of());
    }

    @Override
    public Optional<RegionKeyResolver.RegionKey> fishingKey(Location bobber) {
        return resolve(bobber);
    }

    /** RegionKeyResolver (Fishing 캐스팅마다, 목표 0.05ms). */
    @Override
    public Optional<RegionKey> resolve(Location bobber) {
        Optional<WorldIndex.Entry> found = entryAt(bobber);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorldIndex.Entry e = found.get();
        String biome = e.def().biome();
        Biome actual = bobber.getBlock().getBiome();
        NamespacedKey key =
                RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).getKey(actual);
        if (key != null && !key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
            biome = key.asString();
        }
        Hooks h = hooks;
        Optional<FishingSpot> spot =
                h == null ? Optional.empty() : h.school(hookOwner(bobber), bobber);
        return Optional.of(
                new RegionKey(
                        e.id(),
                        biome,
                        e.areasAt(bobber.getX(), bobber.getY(), bobber.getZ()),
                        spot.isPresent(),
                        spot.map(FishingSpot::id).orElse(null)));
    }

    private static @Nullable Player hookOwner(Location bobber) {
        World world = bobber.getWorld();
        if (world == null) {
            return null;
        }
        for (FishHook hook : world.getNearbyEntitiesByType(FishHook.class, bobber, 1.0)) {
            if (hook.getShooter() instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    @Override
    public Optional<RegionId> current(UUID player) {
        Track t = tracks.get(player);
        return t == null ? Optional.empty() : Optional.ofNullable(t.region);
    }

    public Optional<WorldIndex.Entry> currentEntry(UUID player) {
        return current(player).flatMap(r -> kit.index().get(r));
    }

    public List<String> currentAreas(UUID player) {
        Track t = tracks.get(player);
        return t == null ? List.of() : t.areas;
    }

    public WorldState state(UUID player) {
        return kit.api().players().get(player).map(p -> p.get(COMPONENT)).orElse(WorldState.EMPTY);
    }

    @Override
    public Set<RegionId> visited(UUID player) {
        return state(player).visited();
    }

    @Override
    public boolean hasVisited(UUID player, RegionId region) {
        return visited(player).contains(region);
    }

    @Override
    public CompletableFuture<Boolean> loadVisited(UUID player, RegionId region) {
        if (kit.api().players().get(player).isPresent()) {
            return CompletableFuture.completedFuture(hasVisited(player, region));
        }
        int season = kit.season();
        return kit.api()
                .database()
                .async(c -> WorldRepository.visited(c, player, season, region))
                .thenApplyAsync(Function.identity(), kit.tasks().main());
    }

    @Override
    public UnlockStatus unlockStatus(Player player, RegionId region) {
        return kit.index()
                .get(region)
                .map(e -> status(player, e, false))
                .orElse(new UnlockStatus(region, List.of()));
    }

    public UnlockStatus status(Player player, WorldIndex.Entry entry, boolean ignoreVehicle) {
        HarborPlayer hp = kit.api().players().get(player);
        UUID uuid = player.getUniqueId();
        Hooks h = hooks;
        UnlockRules.Facts facts =
                new UnlockRules.Facts(
                        hp.level(),
                        hp.jobTier().level(),
                        flag -> kit.api().flags().hasPlayer(uuid, flag),
                        item -> kit.hasItem(player, item),
                        hasVisited(uuid, entry.id()),
                        h == null ? null : h.shipType(player));
        return UnlockRules.check(
                entry.def(), entry.ext(), facts, kit::itemName, this::shipName, ignoreVehicle);
    }

    private String shipName(HarborId ship) {
        return kit.api()
                .content()
                .snapshot()
                .get(wiki.creeper.harbor.world.content.ShipDef.TYPE, ship)
                .map(wiki.creeper.harbor.world.content.ShipDef::name)
                .orElse(ship.name());
    }

    @Override
    public RegionInfo highestUnlocked(Player player) {
        List<WorldIndex.Entry> all = kit.index().regions();
        for (int i = all.size() - 1; i >= 0; i--) {
            WorldIndex.Entry e = all.get(i);
            if (status(player, e, true).allowed()) {
                return e.info();
            }
        }
        return all.isEmpty()
                ? new RegionInfo(
                        RegionId.parse("region/harbor"),
                        "",
                        "harbor_main",
                        "",
                        1,
                        0,
                        0,
                        List.of(),
                        null)
                : all.getFirst().info();
    }

    @Override
    public int occupancy(RegionId region) {
        int count = 0;
        for (Track t : tracks.values()) {
            if (region.equals(t.region)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public CompletableFuture<Map<RegionId, Integer>> occupancyAll() {
        return occupancyBoard.all();
    }

    @Override
    public boolean travel(Player player, RegionId region, String reason) {
        Optional<WorldIndex.Entry> entry = kit.index().get(region);
        if (entry.isEmpty()) {
            return false;
        }
        return router.send(player, spawnOf(entry.get()), entry.get().ext().server(), reason);
    }

    /** 지역 안전 지점 (world/regions.yml spawn, 없으면 경계 가운데). */
    public static ServerRouter.Destination spawnOf(WorldIndex.Entry e) {
        RegionExt.Point spawn = e.ext().spawn();
        if (spawn != null) {
            return new ServerRouter.Destination(
                    e.def().world(), spawn.x(), spawn.y(), spawn.z(), spawn.yaw());
        }
        return new ServerRouter.Destination(
                e.def().world(),
                (e.polygon().minX() + e.polygon().maxX()) / 2,
                80,
                (e.polygon().minZ() + e.polygon().maxZ()) / 2,
                0);
    }

    // ------------------------------------------------------------------ 이동 판정

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX()
                && to.getBlockY() == from.getBlockY()
                && to.getBlockZ() == from.getBlockZ()
                && Objects.equals(to.getWorld(), from.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        Location dest = check(player, from, to, null);
        if (dest != null) {
            event.setTo(dest);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX()
                && to.getBlockY() == from.getBlockY()
                && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        Vehicle vehicle = event.getVehicle();
        for (Entity passenger : new ArrayList<>(vehicle.getPassengers())) {
            if (passenger instanceof Player player && check(player, from, to, vehicle) != null) {
                return;
            }
        }
    }

    /**
     * 이동을 반영한다. 막힌 지역이면 밀어내고 되돌아갈 위치를 돌려준다 (PlayerMoveEvent는 setTo로, 탈것은 여기서 직접 옮긴다).
     *
     * @return 밀어냈으면 되돌아갈 위치, 아니면 null
     */
    @Nullable Location check(Player player, Location from, Location to, @Nullable Vehicle vehicle) {
        Track t = tracks.computeIfAbsent(player.getUniqueId(), u -> new Track());
        String worldId = kit.worldId(to);
        int cx = to.getBlockX() >> 4;
        int cz = to.getBlockZ() >> 4;
        long chunk = ((long) worldId.hashCode() << 42) ^ ((long) cx << 21) ^ (cz & 0x1FFFFF);
        boolean chunkChanged = chunk != t.chunk;
        Optional<WorldIndex.Entry> found =
                kit.index()
                        .wholeChunk(worldId, cx, cz)
                        .or(() -> kit.index().at(worldId, to.getX(), to.getZ()));
        WorldIndex.Entry region = found.orElse(null);
        RegionId newId = region == null ? null : region.id();
        if (!Objects.equals(newId, t.region) && region != null && !player.hasPermission(BYPASS)) {
            UnlockStatus status = status(player, region, false);
            if (!status.allowed()) {
                return barrier.pushBack(player, t, region, status, from, to, vehicle);
            }
        }
        t.chunk = chunk;
        if (!Objects.equals(newId, t.region)) {
            transition(player, t, region, true);
        }
        t.areas = region == null ? List.of() : region.areasAt(to.getX(), to.getY(), to.getZ());
        Hooks h = hooks;
        if (chunkChanged && region != null && h != null) {
            h.onChunk(player, region, cx, cz);
        }
        return null;
    }

    private void transition(
            Player player, Track t, WorldIndex.@Nullable Entry next, boolean announce) {
        RegionId prevId = t.region;
        WorldIndex.Entry prev = prevId == null ? null : kit.index().get(prevId).orElse(null);
        t.region = next == null ? null : next.id();
        if (prev != null) {
            Bukkit.getPluginManager()
                    .callEvent(
                            new RegionLeaveEvent(
                                    player, prev.info(), next == null ? null : next.info()));
        }
        if (next == null) {
            return;
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new RegionEnterEvent(
                                player, next.info(), prev == null ? null : prev.info()));
        if (announce) {
            kit.api()
                    .ui()
                    .title(
                            player,
                            kit.api()
                                    .messages()
                                    .get(
                                            "world.region.title",
                                            Placeholder.parsed("name", next.def().name())),
                            kit.api()
                                    .messages()
                                    .get(
                                            "world.region.subtitle",
                                            Placeholder.unparsed(
                                                    "level",
                                                    String.valueOf(next.def().recLevel()))),
                            Duration.ofMillis(300),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(600));
            // 지역 배경음·환경음은 데이터팩 바이옴(Environment Attributes)이 바꾼다. music은 진입 때 한 번 내는 추가 연출
            HarborSounds.play(
                    player,
                    next.def().music(),
                    HarborSounds.Options.DEFAULT.withSource(Sound.Source.MUSIC));
        }
        firstVisit(player, next);
    }

    private void firstVisit(Player player, WorldIndex.Entry region) {
        HarborPlayer hp = kit.api().players().get(player);
        WorldState state = hp.get(COMPONENT);
        // Economy 레시피·상점 해금이 읽는 방문 플래그 (시즌 범위). region_visit와 별개로 빠져 있으면 다시 세운다
        String flag = visitedFlag(region.id());
        if (!kit.api().flags().hasPlayer(player.getUniqueId(), flag)) {
            var _ =
                    kit.api()
                            .flags()
                            .setPlayer(player.getUniqueId(), FlagService.Scope.SEASON, flag);
        }
        if (state.visited().contains(region.id())) {
            return;
        }
        hp.set(COMPONENT, state.withVisited(region.id()));
        UUID uuid = player.getUniqueId();
        int season = kit.season();
        RegionId id = region.id();
        kit.api()
                .writeBehind()
                .enqueue(
                        "player:" + uuid + ":world:visit:" + id,
                        c -> WorldRepository.insertVisit(c, uuid, season, id));
        kit.send(
                player,
                "world.region.first_visit",
                Placeholder.parsed("name", region.def().name()));
        Bukkit.getPluginManager().callEvent(new RegionFirstVisitEvent(player, region.info()));
    }

    /** 지역 첫 방문 플래그 {@code visited/<지역 이름>} (Economy 레시피·상점 해금이 읽는다). */
    public static String visitedFlag(RegionId region) {
        return wiki.creeper.harbor.world.api.WorldFlags.visited(region);
    }

    /** 해금 조건과 현재 진행도 Dialog (예: "Lv 18 / 20"). */
    public void showRequirements(Player player, WorldIndex.Entry region, UnlockStatus status) {
        barrier.showRequirements(player, region, status);
    }

    // ------------------------------------------------------------------ 접속·퇴장

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Track t = tracks.computeIfAbsent(player.getUniqueId(), u -> new Track());
        WorldState.SafePos safe = state(player.getUniqueId()).safe();
        if (safe != null) {
            World w = kit.bukkitWorld(safe.world());
            if (w != null) {
                t.safe = new Location(w, safe.x(), safe.y(), safe.z());
            }
        }
        Optional<WorldIndex.Entry> region = entryAt(WorldKit.loc(player));
        if (region.isPresent()
                && !player.hasPermission(BYPASS)
                && !status(player, region.get(), true).allowed()) {
            // 시즌 초기화로 레벨이 내려간 경우 등: 여관 앞 광장으로
            kit.index().regions().stream()
                    .findFirst()
                    .ifPresent(
                            plaza -> {
                                router.send(player, spawnOf(plaza), plaza.ext().server(), "join");
                                kit.send(player, "world.region.moved_to_plaza");
                            });
            return;
        }
        refresh(player, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracks.remove(event.getPlayer().getUniqueId());
    }

    /** 지금 위치로 지역을 다시 계산한다 (텔레포트 뒤·리로드 뒤). 리로드 때는 막힌 지역이어도 밀어내지 않는다 (다음 이동부터). */
    public void refresh(Player player, boolean announce) {
        if (!player.isOnline()) {
            return;
        }
        Track t = tracks.computeIfAbsent(player.getUniqueId(), u -> new Track());
        Location at = WorldKit.loc(player);
        WorldIndex.Entry region = entryAt(at).orElse(null);
        RegionId id = region == null ? null : region.id();
        if (!Objects.equals(id, t.region)) {
            transition(player, t, region, announce);
        }
        t.areas = region == null ? List.of() : region.areasAt(at.getX(), at.getY(), at.getZ());
        t.chunk = Long.MIN_VALUE;
    }

    /** 콘텐츠 리로드 뒤 모든 플레이어 지역을 다시 계산한다. */
    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            refresh(p, false);
        }
    }

    /** 1초마다: 허용된 지역 안에서 경계로부터 3블록 이상 떨어져 있으면 안전 위치 저장. */
    private void saveSafePositions() {
        double min = kit.balance().safeDistance();
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player p : online) {
            Track t = tracks.get(p.getUniqueId());
            if (t == null || p.isDead()) {
                continue;
            }
            Location at = WorldKit.loc(p);
            RegionId current = t.region;
            if (current != null) {
                Optional<WorldIndex.Entry> e = kit.index().get(current);
                if (e.isEmpty() || e.get().polygon().edgeDistance(at.getX(), at.getZ()) < min) {
                    continue;
                }
            }
            Location prev = t.safe;
            if (prev != null
                    && Objects.equals(prev.getWorld(), at.getWorld())
                    && prev.distanceSquared(at) < 16) {
                continue;
            }
            t.safe = at.clone();
            kit.api()
                    .players()
                    .get(p.getUniqueId())
                    .ifPresent(
                            hp ->
                                    hp.set(
                                            COMPONENT,
                                            hp.get(COMPONENT)
                                                    .withSafe(
                                                            new WorldState.SafePos(
                                                                    kit.worldId(at),
                                                                    at.getX(),
                                                                    at.getY(),
                                                                    at.getZ()))));
        }
    }
}
