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
import wiki.creeper.harbor.world.api.VisitPolicy;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.ServerRouter;

/**
 * Housing (personal dock, docs 20 8.4 'housing', '12. world · housing · traps' chapter 4).
 *
 * <ul>
 *   <li>Layout: one 40×40 cell per account in the harbor_homes world, in spiral order, created on
 *       the first /부두
 *   <li>Size: 24×24 by default, expanded around the center to 32×32 when the best cross-season
 *       reputation tier (NPC ReputationService.bestTier, D11) is 4 or higher (never shrinks)
 *   <li>Protection: block place/break/interact, pistons, liquid flow, explosions and entity spawns
 *       are confined to the plot bounds. Limits of 30 entities · 20 redstone components (plugin
 *       decor Display·Interaction excluded)
 *   <li>Permissions: owner, co-builders (housing_member), visitors (adventure mode). Visit policy
 *       public · builders only (instead of friends) · private
 *   <li>Likes: once a day per visitor per plot. Likes from accounts sharing the owner's IP (Common
 *       AccountLinkReader) are not counted (proposed)
 * </ul>
 *
 * <p>Loading and lookup are handled by {@link PlotStore}, protection by {@link PlotProtection} (a
 * separately registered Listener), permission checks by {@link PlotAccess}, settings and likes by
 * {@link PlotActions}; this class handles HousingService, travel and game modes.
 */
public final class HousingManager implements HousingService, Listener {

    /** A loaded plot. */
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
    private final HousingReturn returns;
    private final Map<UUID, GameMode> savedMode = new HashMap<>();

    /**
     * @param bestTier best cross-season reputation tier (0 without NPC)
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
        this.returns = new HousingReturn(kit, router);
    }

    /** Plot protection Listener (register it right after this object). */
    public Listener protection() {
        return protection;
    }

    public void start() {
        HousingWorld.ensure(kit);
        var _ = kit.tasks().timer(20 * 60, 20 * 60, store::unloadIdle);
        store.subscribe();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var _ = loadOwner(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ loading

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

    // ------------------------------------------------------------------ travel

    /** /부두: my plot (created if missing). */
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
                                                                // Entry on the plot's south
                                                                // edge row, facing in, so
                                                                // building works on arrival
                                                                PlotGrid.Bounds bounds =
                                                                        PlotGrid.bounds(
                                                                                PlotGrid.cell(n),
                                                                                cellSize,
                                                                                size);
                                                                return new double[] {
                                                                    bounds.centerX(),
                                                                    floor + 1,
                                                                    bounds.maxZ() + 0.5,
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

    /** /부두 방문 &lt;name&gt;. */
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

    @Override
    public CompletableFuture<VisitResult> visit(Player player, UUID owner, boolean bypassPolicy) {
        return loadOwner(owner)
                .thenApply(
                        s -> {
                            if (s.isEmpty() || !player.isOnline()) {
                                return VisitResult.NO_PLOT;
                            }
                            if (!bypassPolicy && !can(player, s.get().info, PlotPermission.VISIT)) {
                                kit.send(player, "world.housing.closed");
                                return VisitResult.CLOSED;
                            }
                            go(player, s.get().info);
                            return VisitResult.SENT;
                        });
    }

    @Override
    public CompletableFuture<Boolean> setMember(UUID owner, UUID member, boolean add) {
        return loadOwner(owner)
                .thenCompose(
                        s ->
                                s.isEmpty() || s.get().info.owner().equals(member)
                                        ? CompletableFuture.completedFuture(false)
                                        : actions.applyMember(s.get(), member, add)
                                                .thenApply(v -> true));
    }

    @Override
    public CompletableFuture<Boolean> setVisitPolicy(UUID owner, VisitPolicy policy) {
        return loadOwner(owner)
                .thenCompose(
                        s ->
                                s.isEmpty()
                                        ? CompletableFuture.completedFuture(false)
                                        : actions.applyPolicy(s.get(), policy)
                                                .thenApplyAsync(v -> true, kit.tasks().main()));
    }

    /** Admin command: goes to the plot regardless of the visit policy. */
    public void visitAsAdmin(Player player, PlotInfo plot) {
        go(player, plot);
    }

    /** /부두 나가기: back to where the player was before entering housing. */
    public void leave(Player player) {
        World world = WorldKit.loc(player).getWorld();
        if (world == null || !isHousingWorld(world)) {
            kit.send(player, "world.housing.not_in_housing");
            return;
        }
        returns.leave(player);
    }

    void go(Player player, PlotInfo plot) {
        World here = WorldKit.loc(player).getWorld();
        if (here == null || !isHousingWorld(here)) {
            returns.remember(player);
        }
        String server = kit.balance().b().string("world.housing.server").orElse(null);
        router.send(player, entry(plot), server, "housing");
    }

    /**
     * The plot entry. /부두 입구 always stores a point inside the plot; an entry outside it is the old
     * default (on the road south of the largest plot square), which left the player outside the
     * plot on arrival, so such plots use the south edge row instead.
     */
    private ServerRouter.Destination entry(PlotInfo plot) {
        if (plot.contains(plot.world(), plot.spawnX(), plot.spawnZ())) {
            return new ServerRouter.Destination(
                    plot.world(), plot.spawnX(), plot.spawnY(), plot.spawnZ(), plot.spawnYaw());
        }
        return new ServerRouter.Destination(
                plot.world(),
                (plot.minX() + plot.maxX() + 1) / 2.0,
                kit.balance().housingFloorY() + 1,
                plot.maxZ() + 0.5,
                180);
    }

    // ------------------------------------------------------------------ settings and likes

    public void settings(Player player) {
        actions.settings(player);
    }

    /** /부두 건축자 추가·제거 &lt;name&gt; (add/remove builder). */
    public void member(Player player, String name, boolean add) {
        actions.member(player, name, add);
    }

    /** /부두 입구: sets the current position inside my plot as the entry point. */
    public void setSpawn(Player player) {
        actions.setSpawn(player);
    }

    /** /부두 좋아요: likes the other player's plot the player is standing in. */
    public void like(Player player) {
        actions.like(player);
    }

    /** Admin command: reset (co-builders, tanks, likes). */
    public CompletableFuture<Boolean> adminReset(UUID owner) {
        return store.adminReset(owner);
    }

    /** Admin command: change owner (only if the recipient has no plot). */
    public CompletableFuture<Boolean> adminTransfer(UUID from, UUID to) {
        return store.adminTransfer(from, to);
    }

    // ------------------------------------------------------------------ redstone and tanks
    // (DecorManager)

    void addAnchor(PlotState state, DisplayAnchor anchor) {
        state.anchors.add(anchor);
    }

    void removeAnchor(PlotState state, int slot) {
        state.anchors.removeIf(a -> a.slot() == slot);
    }

    // ------------------------------------------------------------------ game mode

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

    /**
     * Housing world: survival inside a plot the player may build in, otherwise adventure mode. The
     * original mode is restored on leaving.
     */
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
