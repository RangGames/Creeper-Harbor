package wiki.creeper.harbor.world.housing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.account.AccountLinkReader;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.api.VisitPolicy;
import wiki.creeper.harbor.world.api.event.PlotLikedEvent;
import wiki.creeper.harbor.world.api.event.PlotLoadedEvent;
import wiki.creeper.harbor.world.api.event.PlotUnloadedEvent;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.ServerRouter;

/**
 * 하우징 (개인 부두, docs 20 8.4 '하우징', '12. 월드 · 하우징 · 통발' 4장).
 *
 * <ul>
 *   <li>배치: harbor_homes 월드의 40×40 칸을 나선 순서로 계정당 1개, 처음 /부두를 쓸 때 만든다
 *   <li>크기: 기본 24×24, 시즌 간 최고 평판 등급(NPC ReputationService.bestTier, D11)이 4 이상이면 32×32로 가운데 기준 확장
 *       (줄지 않음)
 *   <li>보호: 블록 설치·파괴·상호작용, 피스톤, 액체 흐름, 폭발, 엔티티 생성을 플롯 경계로 막는다. 엔티티 30개 · 레드스톤 부품 20개 제한 (플러그인 장식
 *       Display·Interaction은 제외)
 *   <li>권한: 주인, 공동 건축자(housing_member), 방문객(모험 모드). 방문 정책 공개 · 건축자만(친구 대신) · 비공개
 *   <li>좋아요: 방문객 1명이 플롯 1곳에 하루 1회. 주인과 같은 IP 계정(Common AccountLinkReader)의 좋아요는 세지 않는다 (제안)
 * </ul>
 */
public final class HousingManager implements HousingService, Listener {

    private static final Set<Material> REDSTONE =
            EnumSet.of(
                    Material.REDSTONE_WIRE,
                    Material.REPEATER,
                    Material.COMPARATOR,
                    Material.PISTON,
                    Material.STICKY_PISTON,
                    Material.OBSERVER,
                    Material.DISPENSER,
                    Material.DROPPER,
                    Material.HOPPER,
                    Material.REDSTONE_TORCH,
                    Material.REDSTONE_WALL_TORCH,
                    Material.REDSTONE_LAMP,
                    Material.DAYLIGHT_DETECTOR,
                    Material.TARGET,
                    Material.LEVER,
                    Material.TRIPWIRE_HOOK,
                    Material.SCULK_SENSOR);

    /** 적재된 플롯. */
    static final class PlotState {
        PlotInfo info;
        List<DisplayAnchor> anchors;
        int redstone;

        PlotState(PlotInfo info, List<DisplayAnchor> anchors, int redstone) {
            this.info = info;
            this.anchors = new ArrayList<>(anchors);
            this.redstone = redstone;
        }
    }

    private final WorldKit kit;
    private final ServerRouter router;
    private final Function<UUID, CompletableFuture<Integer>> bestTier;
    private final Map<Long, PlotState> loaded = new HashMap<>();
    private final Map<UUID, Long> byOwner = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();
    private final Map<UUID, GameMode> savedMode = new HashMap<>();

    /**
     * @param bestTier 시즌 간 최고 평판 등급 (NPC가 없으면 0)
     */
    public HousingManager(
            WorldKit kit,
            ServerRouter router,
            Function<UUID, CompletableFuture<Integer>> bestTier) {
        this.kit = kit;
        this.router = router;
        this.bestTier = bestTier;
    }

    public void start() {
        var _ = kit.tasks().timer(20 * 60, 20 * 60, this::unloadIdle);
        for (Player p : Bukkit.getOnlinePlayers()) {
            var _ = loadOwner(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ 적재

    private String worldId() {
        return kit.balance().housingWorld();
    }

    @Override
    public boolean isHousingWorld(World world) {
        return kit.worldId(world).equals(worldId());
    }

    private @Nullable World world() {
        return kit.bukkitWorld(worldId());
    }

    PlotInfo info(HousingRepository.PlotRow row) {
        WorldBalance b = kit.balance();
        PlotGrid.Bounds bounds =
                PlotGrid.bounds(
                        new PlotGrid.Cell(row.gridX(), row.gridZ()), b.housingCell(), row.size());
        return new PlotInfo(
                row.plotId(),
                row.owner(),
                row.ownerName(),
                row.gridX(),
                row.gridZ(),
                row.size(),
                row.theme(),
                row.policy(),
                row.likes(),
                worldId(),
                bounds.minX(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxZ(),
                row.spawnX(),
                row.spawnY(),
                row.spawnZ(),
                row.spawnYaw(),
                row.members());
    }

    /** 플롯 행을 읽어 올린다 (이미 있으면 그대로). */
    private CompletableFuture<Optional<PlotState>> loadById(long plotId) {
        PlotState existing = loaded.get(plotId);
        if (existing != null) {
            return CompletableFuture.completedFuture(Optional.of(existing));
        }
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> row =
                                    HousingRepository.byId(c, plotId);
                            return row.isEmpty()
                                    ? Optional.<Object[]>empty()
                                    : Optional.of(
                                            new Object[] {
                                                row.get(), HousingRepository.anchors(c, plotId)
                                            });
                        })
                .thenApplyAsync(this::install, kit.tasks().main());
    }

    public CompletableFuture<Optional<PlotState>> loadOwner(UUID owner) {
        Long id = byOwner.get(owner);
        if (id != null && loaded.containsKey(id)) {
            return CompletableFuture.completedFuture(Optional.ofNullable(loaded.get(id)));
        }
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> row =
                                    HousingRepository.byOwner(c, owner);
                            return row.isEmpty()
                                    ? Optional.<Object[]>empty()
                                    : Optional.of(
                                            new Object[] {
                                                row.get(),
                                                HousingRepository.anchors(c, row.get().plotId())
                                            });
                        })
                .thenApplyAsync(this::install, kit.tasks().main());
    }

    @SuppressWarnings("unchecked")
    private Optional<PlotState> install(Optional<Object[]> raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        HousingRepository.PlotRow row = (HousingRepository.PlotRow) raw.get()[0];
        List<DisplayAnchor> anchors = (List<DisplayAnchor>) raw.get()[1];
        PlotState existing = loaded.get(row.plotId());
        if (existing != null) {
            return Optional.of(existing);
        }
        PlotState state = new PlotState(info(row), anchors, row.redstone());
        loaded.put(row.plotId(), state);
        byOwner.put(row.owner(), row.plotId());
        Bukkit.getPluginManager().callEvent(new PlotLoadedEvent(state.info));
        growIfNeeded(state);
        return Optional.of(state);
    }

    /** 시즌 간 최고 평판 등급이 기준 이상이면 넓힌다 (줄이지 않음, D11). */
    private void growIfNeeded(PlotState state) {
        WorldBalance b = kit.balance();
        var _ =
                bestTier.apply(state.info.owner())
                        .thenAccept(
                                tier -> {
                                    int size =
                                            PlotGrid.size(
                                                    state.info.size(),
                                                    tier,
                                                    b.housingLargeTier(),
                                                    b.housingBaseSize(),
                                                    b.housingLargeSize());
                                    if (size <= state.info.size()) {
                                        return;
                                    }
                                    long id = state.info.plotId();
                                    var _ =
                                            kit.api()
                                                    .database()
                                                    .asyncVoid(
                                                            c ->
                                                                    HousingRepository.updateSize(
                                                                            c, id, size));
                                    PlotInfo old = state.info;
                                    PlotGrid.Bounds bounds =
                                            PlotGrid.bounds(
                                                    new PlotGrid.Cell(old.gridX(), old.gridZ()),
                                                    b.housingCell(),
                                                    size);
                                    state.info =
                                            new PlotInfo(
                                                    old.plotId(),
                                                    old.owner(),
                                                    old.ownerName(),
                                                    old.gridX(),
                                                    old.gridZ(),
                                                    size,
                                                    old.theme(),
                                                    old.visitPolicy(),
                                                    old.likes(),
                                                    old.world(),
                                                    bounds.minX(),
                                                    bounds.minZ(),
                                                    bounds.maxX(),
                                                    bounds.maxZ(),
                                                    old.spawnX(),
                                                    old.spawnY(),
                                                    old.spawnZ(),
                                                    old.spawnYaw(),
                                                    old.members());
                                    Player owner = Bukkit.getPlayer(old.owner());
                                    if (owner != null) {
                                        kit.send(
                                                owner,
                                                "world.housing.grown",
                                                Placeholder.unparsed("size", String.valueOf(size)));
                                    }
                                });
    }

    /** 1분마다: 주인이 없고 안에 아무도 없는 플롯을 내린다. */
    private void unloadIdle() {
        World world = world();
        for (PlotState state : List.copyOf(loaded.values())) {
            if (Bukkit.getPlayer(state.info.owner()) != null) {
                continue;
            }
            boolean occupied = false;
            if (world != null) {
                for (Player p : world.getPlayers()) {
                    Location at = WorldKit.loc(p);
                    if (state.info.contains(state.info.world(), at.getX(), at.getZ())) {
                        occupied = true;
                        break;
                    }
                }
            }
            if (!occupied) {
                loaded.remove(state.info.plotId());
                byOwner.remove(state.info.owner());
                Bukkit.getPluginManager().callEvent(new PlotUnloadedEvent(state.info));
            }
        }
    }

    Optional<PlotState> stateAt(Location location) {
        World w = location.getWorld();
        if (w == null || !isHousingWorld(w)) {
            return Optional.empty();
        }
        PlotGrid.Cell cell =
                PlotGrid.cellAt(location.getX(), location.getZ(), kit.balance().housingCell());
        long id = PlotGrid.index(cell.x(), cell.z());
        PlotState state = loaded.get(id);
        if (state == null) {
            if (loading.add(id)) {
                var _ = loadById(id).whenComplete((r, e) -> loading.remove(id));
            }
            return Optional.empty();
        }
        return state.info.contains(state.info.world(), location.getX(), location.getZ())
                ? Optional.of(state)
                : Optional.empty();
    }

    Optional<PlotState> state(long plotId) {
        return Optional.ofNullable(loaded.get(plotId));
    }

    // ------------------------------------------------------------------ HousingService

    @Override
    public Optional<PlotInfo> plotAt(Location location) {
        return stateAt(location).map(s -> s.info);
    }

    @Override
    public Optional<PlotInfo> plotOf(UUID owner) {
        Long id = byOwner.get(owner);
        return id == null ? Optional.empty() : Optional.ofNullable(loaded.get(id)).map(s -> s.info);
    }

    @Override
    public CompletableFuture<Optional<PlotInfo>> loadPlot(UUID owner) {
        return loadOwner(owner).thenApply(s -> s.map(x -> x.info));
    }

    @Override
    public boolean can(Player player, PlotInfo plot, PlotPermission permission) {
        if (player.hasPermission("harbor.world.bypass")) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        return switch (permission) {
            case VISIT ->
                    switch (plot.visitPolicy()) {
                        case PUBLIC -> true;
                        case MEMBERS -> plot.isMember(uuid);
                        case PRIVATE -> plot.owner().equals(uuid);
                    };
            case BUILD, EDIT_DISPLAY -> plot.isMember(uuid);
            case MANAGE -> plot.owner().equals(uuid);
        };
    }

    @Override
    public List<DisplayAnchor> displayAnchors(long plotId) {
        PlotState s = loaded.get(plotId);
        return s == null ? List.of() : List.copyOf(s.anchors);
    }

    // ------------------------------------------------------------------ 이동

    /** /부두: 내 플롯 (없으면 만든다). */
    public void home(Player player) {
        UUID uuid = player.getUniqueId();
        var _ =
                loadOwner(uuid)
                        .thenCompose(
                                s ->
                                        s.isPresent()
                                                ? CompletableFuture.completedFuture(s)
                                                : create(player))
                        .thenAccept(
                                s -> {
                                    if (s.isEmpty()) {
                                        kit.send(player, "world.housing.failed");
                                        return;
                                    }
                                    go(player, s.get().info);
                                });
    }

    private CompletableFuture<Optional<PlotState>> create(Player player) {
        UUID uuid = player.getUniqueId();
        WorldBalance b = kit.balance();
        int cellSize = b.housingCell();
        int floor = b.housingFloorY();
        return bestTier.apply(uuid)
                .thenCompose(
                        tier -> {
                            int size =
                                    PlotGrid.size(
                                            0,
                                            tier,
                                            b.housingLargeTier(),
                                            b.housingBaseSize(),
                                            b.housingLargeSize());
                            return kit.api()
                                    .database()
                                    .async(
                                            c ->
                                                    HousingRepository.create(
                                                            c,
                                                            uuid,
                                                            size,
                                                            n -> {
                                                                PlotGrid.Bounds bounds =
                                                                        PlotGrid.bounds(
                                                                                PlotGrid.cell(n),
                                                                                cellSize,
                                                                                cellSize - 8);
                                                                return new double[] {
                                                                    bounds.centerX(),
                                                                    floor + 1,
                                                                    bounds.maxZ() + 3.5,
                                                                    180
                                                                };
                                                            }));
                        })
                .thenCompose(id -> loadById(id))
                .thenApply(
                        s -> {
                            s.ifPresent(x -> kit.send(player, "world.housing.created"));
                            return s;
                        });
    }

    /** /부두 방문 &lt;이름&gt;. */
    public void visit(Player player, String name) {
        var _ =
                kit.api()
                        .database()
                        .async(c -> HousingRepository.uuidByName(c, name))
                        .thenComposeAsync(
                                owner ->
                                        owner.isEmpty()
                                                ? CompletableFuture.completedFuture(
                                                        Optional.<PlotState>empty())
                                                : loadOwner(owner.get()),
                                kit.tasks().main())
                        .thenAccept(
                                s -> {
                                    if (s.isEmpty()) {
                                        kit.send(
                                                player,
                                                "world.housing.not_found",
                                                Placeholder.unparsed("name", name));
                                        return;
                                    }
                                    if (!can(player, s.get().info, PlotPermission.VISIT)) {
                                        kit.send(player, "world.housing.closed");
                                        return;
                                    }
                                    go(player, s.get().info);
                                });
    }

    /** 관리 명령: 방문 정책과 상관없이 플롯으로 간다. */
    public void visitAsAdmin(Player player, PlotInfo plot) {
        go(player, plot);
    }

    void go(Player player, PlotInfo plot) {
        String server = kit.balance().b().string("world.housing.server").orElse(null);
        router.send(
                player,
                new ServerRouter.Destination(
                        plot.world(), plot.spawnX(), plot.spawnY(), plot.spawnZ(), plot.spawnYaw()),
                server,
                "housing");
    }

    // ------------------------------------------------------------------ 설정·좋아요

    public void settings(Player player) {
        Optional<PlotInfo> plot = plotOf(player.getUniqueId());
        if (plot.isEmpty()) {
            kit.send(player, "world.housing.no_plot");
            return;
        }
        var msg = kit.api().messages();
        List<wiki.creeper.harbor.core.api.Dialogs.Choice> choices = new ArrayList<>();
        List<VisitPolicy> policies =
                List.of(VisitPolicy.PUBLIC, VisitPolicy.MEMBERS, VisitPolicy.PRIVATE);
        for (VisitPolicy p : policies) {
            choices.add(
                    new wiki.creeper.harbor.core.api.Dialogs.Choice(
                            msg.get("world.housing.policy." + p.key()),
                            msg.get("world.housing.policy_tip." + p.key())));
        }
        kit.api()
                .dialogs()
                .choose(
                        player,
                        msg.get("world.housing.settings_title"),
                        List.of(
                                msg.get(
                                        "world.housing.settings_body",
                                        Placeholder.unparsed(
                                                "policy",
                                                plain(
                                                        "world.housing.policy."
                                                                + plot.get()
                                                                        .visitPolicy()
                                                                        .key())))),
                        choices,
                        3,
                        index -> setPolicy(player, policies.get(index)));
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(kit.api().messages().get(key));
    }

    private void setPolicy(Player player, VisitPolicy policy) {
        Long id = byOwner.get(player.getUniqueId());
        PlotState s = id == null ? null : loaded.get(id);
        if (s == null) {
            return;
        }
        PlotInfo o = s.info;
        s.info =
                new PlotInfo(
                        o.plotId(),
                        o.owner(),
                        o.ownerName(),
                        o.gridX(),
                        o.gridZ(),
                        o.size(),
                        o.theme(),
                        policy,
                        o.likes(),
                        o.world(),
                        o.minX(),
                        o.minZ(),
                        o.maxX(),
                        o.maxZ(),
                        o.spawnX(),
                        o.spawnY(),
                        o.spawnZ(),
                        o.spawnYaw(),
                        o.members());
        long plotId = o.plotId();
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(c -> HousingRepository.updatePolicy(c, plotId, policy));
        kit.send(
                player,
                "world.housing.policy_set",
                Placeholder.unparsed("policy", plain("world.housing.policy." + policy.key())));
    }

    /** /부두 건축자 추가·제거 &lt;이름&gt;. */
    public void member(Player player, String name, boolean add) {
        Long id = byOwner.get(player.getUniqueId());
        PlotState s = id == null ? null : loaded.get(id);
        if (s == null) {
            kit.send(player, "world.housing.no_plot");
            return;
        }
        long plotId = s.info.plotId();
        var _ =
                kit.api()
                        .database()
                        .async(
                                c -> {
                                    Optional<UUID> target = HousingRepository.uuidByName(c, name);
                                    if (target.isPresent()) {
                                        HousingRepository.member(c, plotId, target.get(), add);
                                    }
                                    return target;
                                })
                        .thenAcceptAsync(
                                target -> {
                                    if (target.isEmpty()) {
                                        kit.send(
                                                player,
                                                "world.housing.not_found",
                                                Placeholder.unparsed("name", name));
                                        return;
                                    }
                                    Set<UUID> members = new HashSet<>(s.info.members());
                                    if (add) {
                                        members.add(target.get());
                                    } else {
                                        members.remove(target.get());
                                    }
                                    PlotInfo o = s.info;
                                    s.info =
                                            new PlotInfo(
                                                    o.plotId(),
                                                    o.owner(),
                                                    o.ownerName(),
                                                    o.gridX(),
                                                    o.gridZ(),
                                                    o.size(),
                                                    o.theme(),
                                                    o.visitPolicy(),
                                                    o.likes(),
                                                    o.world(),
                                                    o.minX(),
                                                    o.minZ(),
                                                    o.maxX(),
                                                    o.maxZ(),
                                                    o.spawnX(),
                                                    o.spawnY(),
                                                    o.spawnZ(),
                                                    o.spawnYaw(),
                                                    members);
                                    kit.send(
                                            player,
                                            add
                                                    ? "world.housing.member_added"
                                                    : "world.housing.member_removed",
                                            Placeholder.unparsed("name", name));
                                },
                                kit.tasks().main());
    }

    /** /부두 입구: 내 플롯 안 지금 위치를 입장 지점으로. */
    public void setSpawn(Player player) {
        Location at = WorldKit.loc(player);
        Optional<PlotState> s = stateAt(at);
        if (s.isEmpty() || !s.get().info.owner().equals(player.getUniqueId())) {
            kit.send(player, "world.housing.not_own_plot");
            return;
        }
        PlotInfo o = s.get().info;
        s.get().info =
                new PlotInfo(
                        o.plotId(),
                        o.owner(),
                        o.ownerName(),
                        o.gridX(),
                        o.gridZ(),
                        o.size(),
                        o.theme(),
                        o.visitPolicy(),
                        o.likes(),
                        o.world(),
                        o.minX(),
                        o.minZ(),
                        o.maxX(),
                        o.maxZ(),
                        at.getX(),
                        at.getY(),
                        at.getZ(),
                        at.getYaw(),
                        o.members());
        long id = o.plotId();
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(
                                c ->
                                        HousingRepository.updateSpawn(
                                                c,
                                                id,
                                                at.getX(),
                                                at.getY(),
                                                at.getZ(),
                                                at.getYaw()));
        kit.send(player, "world.housing.spawn_set");
    }

    /** /부두 좋아요: 지금 서 있는 남의 플롯. */
    public void like(Player player) {
        Optional<PlotState> s = stateAt(WorldKit.loc(player));
        if (s.isEmpty()) {
            kit.send(player, "world.housing.not_in_plot");
            return;
        }
        PlotInfo plot = s.get().info;
        UUID uuid = player.getUniqueId();
        if (plot.owner().equals(uuid)) {
            kit.send(player, "world.housing.like_self");
            return;
        }
        LocalDate day = kit.api().time().gameDay();
        var _ =
                kit.api()
                        .database()
                        .async(
                                c -> {
                                    if (AccountLinkReader.areLinked(c, plot.owner(), uuid)) {
                                        return -2;
                                    }
                                    return HousingRepository.like(c, plot.plotId(), uuid, day);
                                })
                        .thenAcceptAsync(
                                likes -> {
                                    if (likes == -2) {
                                        kit.send(player, "world.housing.like_linked");
                                        return;
                                    }
                                    if (likes < 0) {
                                        kit.send(player, "world.housing.like_already");
                                        return;
                                    }
                                    PlotInfo o = s.get().info;
                                    s.get().info =
                                            new PlotInfo(
                                                    o.plotId(),
                                                    o.owner(),
                                                    o.ownerName(),
                                                    o.gridX(),
                                                    o.gridZ(),
                                                    o.size(),
                                                    o.theme(),
                                                    o.visitPolicy(),
                                                    likes,
                                                    o.world(),
                                                    o.minX(),
                                                    o.minZ(),
                                                    o.maxX(),
                                                    o.maxZ(),
                                                    o.spawnX(),
                                                    o.spawnY(),
                                                    o.spawnZ(),
                                                    o.spawnYaw(),
                                                    o.members());
                                    kit.send(
                                            player,
                                            "world.housing.liked",
                                            Placeholder.unparsed("name", o.ownerName()),
                                            Placeholder.unparsed("likes", String.valueOf(likes)));
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new PlotLikedEvent(
                                                            player, s.get().info, likes));
                                },
                                kit.tasks().main());
    }

    /** 관리 명령: 초기화 (공동 건축자·수조·좋아요). */
    public CompletableFuture<Boolean> adminReset(UUID owner) {
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> row =
                                    HousingRepository.byOwner(c, owner);
                            if (row.isPresent()) {
                                HousingRepository.reset(c, row.get().plotId());
                            }
                            return row.map(HousingRepository.PlotRow::plotId);
                        })
                .thenApplyAsync(
                        id -> {
                            id.ifPresent(
                                    i -> {
                                        loaded.remove(i);
                                        byOwner.remove(owner);
                                    });
                            return id.isPresent();
                        },
                        kit.tasks().main());
    }

    /** 관리 명령: 주인 바꾸기 (받는 쪽에 플롯이 없을 때만). */
    public CompletableFuture<Boolean> adminTransfer(UUID from, UUID to) {
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> src =
                                    HousingRepository.byOwner(c, from);
                            if (src.isEmpty() || HousingRepository.byOwner(c, to).isPresent()) {
                                return false;
                            }
                            HousingRepository.transfer(c, src.get().plotId(), to);
                            return true;
                        })
                .thenApplyAsync(
                        ok -> {
                            Long id = byOwner.remove(from);
                            if (id != null) {
                                loaded.remove(id);
                            }
                            return ok;
                        },
                        kit.tasks().main());
    }

    // ------------------------------------------------------------------ 레드스톤·수조 (DecorManager)

    void addAnchor(PlotState state, DisplayAnchor anchor) {
        state.anchors.add(anchor);
    }

    void removeAnchor(PlotState state, int slot) {
        state.anchors.removeIf(a -> a.slot() == slot);
    }

    // ------------------------------------------------------------------ 보호

    private boolean allowedAt(Player player, Location at) {
        if (player.hasPermission("harbor.world.bypass")) {
            return true;
        }
        Optional<PlotState> s = stateAt(at);
        return s.isPresent() && can(player, s.get().info, PlotPermission.BUILD);
    }

    private boolean housing(@Nullable World world) {
        return world != null && isHousingWorld(world);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!housing(block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (!allowedAt(player, block.getLocation())) {
            event.setCancelled(true);
            kit.actionBar(player, "world.housing.no_build");
            return;
        }
        if (REDSTONE.contains(block.getType())) {
            Optional<PlotState> s = stateAt(block.getLocation());
            if (s.isPresent()) {
                if (s.get().redstone >= kit.balance().housingRedstoneLimit()) {
                    event.setCancelled(true);
                    kit.actionBar(
                            player,
                            "world.housing.redstone_limit",
                            Placeholder.unparsed(
                                    "limit", String.valueOf(kit.balance().housingRedstoneLimit())));
                    return;
                }
                s.get().redstone++;
                saveRedstone(s.get());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!housing(block.getWorld())) {
            return;
        }
        if (!allowedAt(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
            kit.actionBar(event.getPlayer(), "world.housing.no_build");
            return;
        }
        if (REDSTONE.contains(block.getType())) {
            stateAt(block.getLocation())
                    .ifPresent(
                            s -> {
                                s.redstone = Math.max(0, s.redstone - 1);
                                saveRedstone(s);
                            });
        }
    }

    private void saveRedstone(PlotState s) {
        long id = s.info.plotId();
        int count = s.redstone;
        kit.api()
                .writeBehind()
                .enqueue(
                        "world:plot:" + id + ":redstone",
                        c -> HousingRepository.updateRedstone(c, id, count));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null
                || !housing(block.getWorld())
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!allowedAt(event.getPlayer(), block.getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (housing(event.getBlock().getWorld())
                && !allowedAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent event) {
        if (housing(event.getBlock().getWorld())
                && !allowedAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        Location at = event.getBlock().getLocation();
        if (player == null || !housing(at.getWorld())) {
            return;
        }
        if (!allowedAt(player, at) || overEntityLimit(at)) {
            event.setCancelled(true);
            kit.actionBar(
                    player,
                    "world.housing.entity_limit",
                    Placeholder.unparsed(
                            "limit", String.valueOf(kit.balance().housingEntityLimit())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        Location at = event.getBlock().getLocation();
        if (player == null || !housing(at.getWorld())) {
            return;
        }
        if (!allowedAt(player, at) || overEntityLimit(at)) {
            event.setCancelled(true);
            kit.actionBar(
                    player,
                    "world.housing.entity_limit",
                    Placeholder.unparsed(
                            "limit", String.valueOf(kit.balance().housingEntityLimit())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangBreak(HangingBreakByEntityEvent event) {
        Location at = WorldKit.loc(event.getEntity());
        if (!housing(at.getWorld())) {
            return;
        }
        if (!(event.getRemover() instanceof Player player) || !allowedAt(player, at)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        Location at = WorldKit.loc(target);
        if (!housing(at.getWorld()) || target instanceof Interaction) {
            return; // 개인 어항·가구 클릭(Interaction)은 Collection·장식이 처리한다
        }
        if (!allowedAt(event.getPlayer(), at)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Location at = WorldKit.loc(event.getEntity());
        if (!housing(at.getWorld()) || event.getEntity() instanceof Player) {
            return;
        }
        if (!(event.getDamager() instanceof Player player) || !allowedAt(player, at)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Location at = event.getLocation();
        if (!housing(at.getWorld())) {
            return;
        }
        switch (event.getSpawnReason()) {
            case CUSTOM, COMMAND, DEFAULT -> {
                // 플러그인 연출 (개인 어항 리그 등)
            }
            case SPAWNER_EGG -> {
                if (stateAt(at).isEmpty() || overEntityLimit(at)) {
                    event.setCancelled(true);
                }
            }
            default -> event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (housing(event.getBlock().getWorld())
                && crosses(
                        event.getBlock(),
                        event.getBlocks(),
                        event.getDirection().getModX(),
                        event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        if (housing(event.getBlock().getWorld())
                && crosses(
                        event.getBlock(),
                        event.getBlocks(),
                        event.getDirection().getModX(),
                        event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    private boolean crosses(Block piston, List<Block> blocks, int dx, int dz) {
        Optional<PlotState> home = stateAt(piston.getLocation());
        if (home.isEmpty()) {
            return true;
        }
        PlotInfo plot = home.get().info;
        for (Block b : blocks) {
            if (!plot.contains(plot.world(), b.getX() + 0.5, b.getZ() + 0.5)
                    || !plot.contains(plot.world(), b.getX() + dx + 0.5, b.getZ() + dz + 0.5)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        if (!housing(from.getWorld())) {
            return;
        }
        Optional<PlotState> a = stateAt(from.getLocation());
        Optional<PlotState> b = stateAt(event.getToBlock().getLocation());
        if (a.isEmpty() || b.isEmpty() || a.get() != b.get()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (housing(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        if (housing(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    /** 플롯 안 엔티티 수 (플레이어·저장하지 않는 엔티티·플러그인 장식 제외). */
    private boolean overEntityLimit(Location at) {
        Optional<PlotState> s = stateAt(at);
        World world = at.getWorld();
        if (s.isEmpty() || world == null) {
            return true;
        }
        PlotInfo p = s.get().info;
        BoundingBox box =
                new BoundingBox(
                        p.minX(),
                        world.getMinHeight(),
                        p.minZ(),
                        p.maxX() + 1,
                        world.getMaxHeight(),
                        p.maxZ() + 1);
        int count = 0;
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Player
                    || !e.isPersistent()
                    || e.getPersistentDataContainer().has(kit.key("decor"))) {
                continue;
            }
            count++;
        }
        return count >= kit.balance().housingEntityLimit();
    }

    // ------------------------------------------------------------------ 게임 모드

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var _ = loadOwner(event.getPlayer().getUniqueId());
        var _ = kit.tasks().later(5, () -> updateMode(event.getPlayer()));
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        updateMode(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (event.getFrom().getBlockX() >> 4 == to.getBlockX() >> 4
                && event.getFrom().getBlockZ() >> 4 == to.getBlockZ() >> 4) {
            return;
        }
        if (housing(to.getWorld())) {
            updateMode(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameMode mode = savedMode.remove(player.getUniqueId());
        if (mode != null) {
            player.setGameMode(mode);
        }
    }

    /** 하우징 월드: 건축 권한이 있는 플롯 안이면 생존, 아니면 모험 모드. 나가면 원래 모드로. */
    private void updateMode(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        GameMode current = player.getGameMode();
        if (!housing(player.getWorld())) {
            GameMode saved = savedMode.remove(uuid);
            if (saved != null) {
                player.setGameMode(saved);
            }
            return;
        }
        if (current != GameMode.SURVIVAL && current != GameMode.ADVENTURE) {
            return;
        }
        savedMode.putIfAbsent(uuid, current);
        Optional<PlotState> s = stateAt(WorldKit.loc(player));
        GameMode wanted =
                s.isPresent() && can(player, s.get().info, PlotPermission.BUILD)
                        ? GameMode.SURVIVAL
                        : GameMode.ADVENTURE;
        if (current != wanted) {
            player.setGameMode(wanted);
        }
    }

    public int loadedCount() {
        return loaded.size();
    }
}
