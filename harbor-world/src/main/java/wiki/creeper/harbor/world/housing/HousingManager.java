package wiki.creeper.harbor.world.housing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;
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
 *
 * <p>적재·조회는 {@link PlotStore}, 보호는 {@link PlotProtection}(따로 등록하는 Listener), 권한 판정은 {@link
 * PlotAccess}, 설정·좋아요는 {@link PlotActions}가 맡고, 이 클래스는 HousingService·이동·게임 모드를 맡는다.
 */
public final class HousingManager implements HousingService, Listener {

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
    private final PlotStore store;
    private final PlotActions actions;
    private final PlotProtection protection;
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
        this.store = new PlotStore(kit, bestTier);
        this.actions = new PlotActions(kit, store);
        this.protection = new PlotProtection(kit, store);
    }

    /** 플롯 보호 Listener (이 객체 바로 뒤에 등록한다). */
    public Listener protection() {
        return protection;
    }

    public void start() {
        var _ = kit.tasks().timer(20 * 60, 20 * 60, store::unloadIdle);
        for (Player p : Bukkit.getOnlinePlayers()) {
            var _ = loadOwner(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ 적재

    @Override
    public boolean isHousingWorld(World world) {
        return store.isHousingWorld(world);
    }

    PlotInfo info(HousingRepository.PlotRow row) {
        return store.info(row);
    }

    public CompletableFuture<Optional<PlotState>> loadOwner(UUID owner) {
        return store.loadOwner(owner);
    }

    Optional<PlotState> stateAt(Location location) {
        return store.stateAt(location);
    }

    Optional<PlotState> state(long plotId) {
        return store.state(plotId);
    }

    // ------------------------------------------------------------------ HousingService

    @Override
    public Optional<PlotInfo> plotAt(Location location) {
        return stateAt(location).map(s -> s.info);
    }

    @Override
    public Optional<PlotInfo> plotOf(UUID owner) {
        return Optional.ofNullable(store.ofOwner(owner)).map(s -> s.info);
    }

    @Override
    public CompletableFuture<Optional<PlotInfo>> loadPlot(UUID owner) {
        return loadOwner(owner).thenApply(s -> s.map(x -> x.info));
    }

    @Override
    public boolean can(Player player, PlotInfo plot, PlotPermission permission) {
        return PlotAccess.can(player, plot, permission);
    }

    @Override
    public List<DisplayAnchor> displayAnchors(long plotId) {
        PlotState s = store.state(plotId).orElse(null);
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
                .thenCompose(id -> store.loadById(id))
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
        actions.settings(player);
    }

    /** /부두 건축자 추가·제거 &lt;이름&gt;. */
    public void member(Player player, String name, boolean add) {
        actions.member(player, name, add);
    }

    /** /부두 입구: 내 플롯 안 지금 위치를 입장 지점으로. */
    public void setSpawn(Player player) {
        actions.setSpawn(player);
    }

    /** /부두 좋아요: 지금 서 있는 남의 플롯. */
    public void like(Player player) {
        actions.like(player);
    }

    /** 관리 명령: 초기화 (공동 건축자·수조·좋아요). */
    public CompletableFuture<Boolean> adminReset(UUID owner) {
        return store.adminReset(owner);
    }

    /** 관리 명령: 주인 바꾸기 (받는 쪽에 플롯이 없을 때만). */
    public CompletableFuture<Boolean> adminTransfer(UUID from, UUID to) {
        return store.adminTransfer(from, to);
    }

    // ------------------------------------------------------------------ 레드스톤·수조 (DecorManager)

    void addAnchor(PlotState state, DisplayAnchor anchor) {
        state.anchors.add(anchor);
    }

    void removeAnchor(PlotState state, int slot) {
        state.anchors.removeIf(a -> a.slot() == slot);
    }

    // ------------------------------------------------------------------ 게임 모드

    private boolean housing(@Nullable World world) {
        return world != null && isHousingWorld(world);
    }

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
        return store.loadedCount();
    }
}
