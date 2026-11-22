package wiki.creeper.harbor.world;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.jobs.api.JobsService;
import wiki.creeper.harbor.world.api.ChartService;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.FishingSpotService;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.RegionService;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.api.ShipService;
import wiki.creeper.harbor.world.api.TreasureService;
import wiki.creeper.harbor.world.bridge.NpcBridge;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.bridge.SeasonBridge;
import wiki.creeper.harbor.world.chart.ChartManager;
import wiki.creeper.harbor.world.command.WorldCommands;
import wiki.creeper.harbor.world.content.DecorDef;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.content.WorldContent;
import wiki.creeper.harbor.world.env.EnvironmentManager;
import wiki.creeper.harbor.world.housing.DecorManager;
import wiki.creeper.harbor.world.housing.HousingManager;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.ServerRouter;
import wiki.creeper.harbor.world.region.WorldIndex;
import wiki.creeper.harbor.world.ride.RideManager;
import wiki.creeper.harbor.world.sea.GhostShipController;
import wiki.creeper.harbor.world.sea.SeaEventManager;
import wiki.creeper.harbor.world.sea.SeaEventScheduler;
import wiki.creeper.harbor.world.sea.TournamentManager;
import wiki.creeper.harbor.world.ship.ShipManager;
import wiki.creeper.harbor.world.spot.SpotManager;
import wiki.creeper.harbor.world.treasure.TreasureManager;

/**
 * Harbor-World (docs 20 chapter 8): rules of places and movement. Region detection, entry
 * conditions and push-back, sea charts, environment debuffs, ships and rides, fish schools,
 * treasure maps, the sea event scheduler (storms, ghost ships, great fish migrations, weekly
 * fishing tournaments), and housing (plots, permissions, visits, likes, decor, personal aquarium
 * slots). Implements and registers the Fishing extension points RegionKeyResolver and
 * TreasureDropHook; other modules use only the services and events in {@code
 * wiki.creeper.harbor.world.api}.
 */
public final class HarborWorldPlugin extends HarborModule {

    private @Nullable DecorManager decorManager;
    private @Nullable ShipManager ships;
    private @Nullable RideManager rides;
    private @Nullable SeaEventManager events;

    @Override
    public String moduleId() {
        return "world";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        WorldContent.TYPES.forEach(ctx::contentType);
        registerChecks(ctx);
        ctx.playerComponent(RegionManager.COMPONENT);

        ServicesManager sm = getServer().getServicesManager();
        Objects.requireNonNull(sm.load(JobsService.class), "Harbor-Jobs가 켜져 있어야 합니다");
        FishingService fishing =
                Objects.requireNonNull(sm.load(FishingService.class), "Harbor-Fishing이 켜져 있어야 합니다");
        boolean npc = getServer().getPluginManager().getPlugin("Harbor-NPC") != null;
        Function<UUID, CompletableFuture<Integer>> bestTier =
                npc ? NpcBridge::bestTier : uuid -> CompletableFuture.completedFuture(0);

        WorldKit kit = new WorldKit(this, api, ctx.tasks(), getSLF4JLogger());
        ServerRouter router = new ServerRouter(kit);
        RegionManager regions = new RegionManager(kit, router);
        SpotManager spots = new SpotManager(kit);
        ChartManager charts = new ChartManager(kit, regions, spots::all);
        Payments payments = Payments.create(kit);
        ShipManager ship = new ShipManager(kit, regions, payments);
        EnvironmentManager env = new EnvironmentManager(kit, regions);
        TreasureManager treasure = new TreasureManager(kit);
        SeaEventManager sea = new SeaEventManager(kit, regions);
        TournamentManager tournament = new TournamentManager(kit);
        GhostShipController ghosts = new GhostShipController(kit, regions, treasure);
        SeaEventScheduler scheduler = new SeaEventScheduler(kit, sea, tournament);
        HousingManager housing = new HousingManager(kit, router, bestTier);
        DecorManager decor = new DecorManager(kit, housing, payments, bestTier);
        this.decorManager = decor;
        RideManager ride = new RideManager(kit, regions);
        this.ships = ship;
        this.rides = ride;
        this.events = sea;

        regions.hooks(
                new RegionManager.Hooks() {
                    @Override
                    public void onChunk(Player player, WorldIndex.Entry region, int cx, int cz) {
                        charts.onChunk(player, region, cx, cz);
                    }

                    @Override
                    public @Nullable HarborId shipType(Player player) {
                        return ship.shipType(player);
                    }

                    @Override
                    public Optional<FishingSpot> school(@Nullable Player shooter, Location bobber) {
                        return spots.school(shooter, bobber);
                    }
                });
        ship.seaEvents(sea);
        sea.controllers(ghosts, tournament);

        for (Listener l :
                new Listener[] {
                    router,
                    regions,
                    regions.barrier(),
                    charts,
                    ship,
                    env,
                    treasure,
                    sea,
                    tournament,
                    ghosts,
                    housing,
                    housing.protection(),
                    decor,
                    decor.placement(),
                    ride
                }) {
            ctx.listen(l);
        }
        ctx.listen(new ReloadListener(kit, regions, charts, spots));
        ctx.handoffBlocker(ship.handoffBlocker());
        ctx.handoffBlocker(ride.handoffBlocker());
        // Decor cleanup right before the save (it has side effects, so last, after the deny checks)
        ctx.handoffBlocker(decor.handoffBlocker());

        fishing.registerRegionResolver(regions);
        fishing.registerTreasureHook(treasure);

        // Region index: content with module types is re-read after enable, so build it once more on
        // the
        // next tick
        kit.setIndex(WorldIndex.build(api.content().snapshot()));
        var _ =
                ctx.tasks()
                        .sync(
                                () -> {
                                    kit.setIndex(WorldIndex.build(api.content().snapshot()));
                                    regions.refreshAll();
                                });

        regions.start();
        spots.start();
        ship.start();
        env.start();
        treasure.start();
        sea.start();
        scheduler.start();
        housing.start();
        decor.start();
        ride.start();

        WorldCommands commands =
                new WorldCommands(
                        kit, regions, charts, ship, housing, decor, ride, sea, scheduler, spots);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        sm.register(RegionService.class, regions, this, ServicePriority.Normal);
        sm.register(ChartService.class, charts, this, ServicePriority.Normal);
        sm.register(TreasureService.class, treasure, this, ServicePriority.Normal);
        sm.register(ShipService.class, ship, this, ServicePriority.Normal);
        sm.register(SeaEventService.class, sea, this, ServicePriority.Normal);
        sm.register(FishingSpotService.class, spots, this, ServicePriority.Normal);
        sm.register(HousingService.class, housing, this, ServicePriority.Normal);

        if (npc) {
            NpcBridge.registerActions(
                    this,
                    commands::yard,
                    housing::home,
                    decor::openShop,
                    p -> ride.board(p, null),
                    p -> commands.chartDialog(p, null));
        }
        if (getServer().getPluginManager().getPlugin("Harbor-Season") != null) {
            SeasonBridge.register();
        }
    }

    /** Content checks that need the server API (block and entity names). */
    private static void registerChecks(ModuleContext ctx) {
        ctx.contentCheck(
                DecorDef.TYPE,
                (d, check) -> {
                    String raw = d.material();
                    if (raw != null) {
                        Material m = Material.matchMaterial(raw);
                        if (m == null
                                || ((d.kind() == DecorDef.Kind.BLOCK
                                                || d.kind() == DecorDef.Kind.TANK)
                                        && !m.isBlock())) {
                            check.error("알 수 없는 블록 '" + raw + "'");
                        }
                    }
                });
        ctx.contentCheck(
                ShipDef.TYPE,
                (s, check) -> {
                    try {
                        var _ = EntityType.valueOf(s.entityName());
                    } catch (IllegalArgumentException e) {
                        check.error("보트 종류가 없습니다: " + s.entityName());
                    }
                });
        ctx.contentCheck(
                SeaEventDef.TYPE,
                (e, check) -> {
                    SeaEventDef.Ghost ghost = e.ghost();
                    if (ghost == null) {
                        return;
                    }
                    for (SeaEventDef.PirateMob m : ghost.mobs()) {
                        checkItem(m.weapon(), check);
                        checkItem(m.helmet(), check);
                    }
                });
    }

    private static void checkItem(
            @Nullable String key, wiki.creeper.harbor.common.content.ContentCheck.Context check) {
        if (key != null && Material.matchMaterial(key) == null) {
            check.error("알 수 없는 아이템 '" + key + "'");
        }
    }

    /**
     * Content reload: builds the new index fully on a virtual thread, swaps it in and recomputes
     * every player's region (even inside a locked region, enforcement starts from the next move).
     */
    public static final class ReloadListener implements Listener {

        private final WorldKit kit;
        private final RegionManager regions;
        private final ChartManager charts;
        private final SpotManager spots;

        ReloadListener(
                WorldKit kit, RegionManager regions, ChartManager charts, SpotManager spots) {
            this.kit = kit;
            this.regions = regions;
            this.charts = charts;
            this.spots = spots;
        }

        @EventHandler
        public void onReload(ContentReloadedEvent event) {
            var snapshot = event.snapshot();
            var _ =
                    kit.tasks()
                            .supplyAsync(() -> WorldIndex.build(snapshot))
                            .thenAcceptAsync(
                                    index -> {
                                        kit.setIndex(index);
                                        charts.clearCache();
                                        spots.reset();
                                        regions.refreshAll();
                                    },
                                    kit.tasks().main());
        }
    }

    @Override
    protected void disable() {
        ShipManager s = ships;
        if (s != null) {
            s.shutdown();
        }
        RideManager r = rides;
        if (r != null) {
            r.shutdown();
        }
        SeaEventManager e = events;
        if (e != null) {
            e.shutdown();
        }
        DecorManager d = decorManager;
        if (d != null) {
            d.flushCredits(true);
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
