package wiki.creeper.harbor.world.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.ChartService;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.chart.ChartManager;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.housing.DecorManager;
import wiki.creeper.harbor.world.housing.HousingManager;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;
import wiki.creeper.harbor.world.ride.RideManager;
import wiki.creeper.harbor.world.sea.SeaEventManager;
import wiki.creeper.harbor.world.sea.SeaEventScheduler;
import wiki.creeper.harbor.world.ship.ShipManager;
import wiki.creeper.harbor.world.spot.SpotManager;

/** /해도 · /부두 · /선박 · /조선소 · /비행선 · /노틸러스, /harbor world … (docs 20 8.5). */
public final class WorldCommands {

    private final WorldKit kit;
    private final RegionManager regions;
    private final ChartManager charts;
    private final ShipManager ships;
    private final HousingManager housing;
    private final DecorManager decor;
    private final RideManager rides;
    private final SeaEventManager events;
    private final SeaEventScheduler scheduler;
    private final SpotManager spots;

    public WorldCommands(
            WorldKit kit,
            RegionManager regions,
            ChartManager charts,
            ShipManager ships,
            HousingManager housing,
            DecorManager decor,
            RideManager rides,
            SeaEventManager events,
            SeaEventScheduler scheduler,
            SpotManager spots) {
        this.kit = kit;
        this.regions = regions;
        this.charts = charts;
        this.ships = ships;
        this.housing = housing;
        this.decor = decor;
        this.rides = rides;
        this.events = events;
        this.scheduler = scheduler;
        this.spots = spots;
    }

    // ------------------------------------------------------------------ 플레이어 명령

    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("해도")
                        .requires(src -> src.getSender().hasPermission("harbor.world.chart"))
                        .executes(ctx -> player(ctx, p -> chartDialog(p, null)))
                        .then(
                                regionArg()
                                        .executes(
                                                ctx ->
                                                        player(
                                                                ctx,
                                                                p ->
                                                                        chartDialog(
                                                                                p,
                                                                                region(ctx)
                                                                                        .orElse(
                                                                                                null)))))
                        .then(
                                Commands.literal("지도")
                                        .then(
                                                regionArg()
                                                        .executes(
                                                                ctx ->
                                                                        player(
                                                                                ctx,
                                                                                p ->
                                                                                        chartMap(
                                                                                                p,
                                                                                                ctx)))))
                        .build(),
                "지역 해도 완성도",
                List.of("chart"));
        commands.register(
                Commands.literal("부두")
                        .requires(src -> src.getSender().hasPermission("harbor.world.housing"))
                        .executes(ctx -> player(ctx, housing::home))
                        .then(
                                Commands.literal("방문")
                                        .then(
                                                Commands.argument("name", StringArgumentType.word())
                                                        .executes(
                                                                ctx ->
                                                                        player(
                                                                                ctx,
                                                                                p ->
                                                                                        housing
                                                                                                .visit(
                                                                                                        p,
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        ctx,
                                                                                                                        "name"))))))
                        .then(
                                Commands.literal("설정")
                                        .executes(ctx -> player(ctx, housing::settings)))
                        .then(Commands.literal("좋아요").executes(ctx -> player(ctx, housing::like)))
                        .then(
                                Commands.literal("입구")
                                        .executes(ctx -> player(ctx, housing::setSpawn)))
                        .then(
                                Commands.literal("장식")
                                        .executes(ctx -> player(ctx, decor::openPalette)))
                        .then(Commands.literal("상점").executes(ctx -> player(ctx, decor::openShop)))
                        .then(
                                Commands.literal("건축자")
                                        .then(
                                                Commands.literal("추가")
                                                        .then(
                                                                Commands.argument(
                                                                                "name",
                                                                                StringArgumentType
                                                                                        .word())
                                                                        .executes(
                                                                                ctx ->
                                                                                        player(
                                                                                                ctx,
                                                                                                p ->
                                                                                                        housing
                                                                                                                .member(
                                                                                                                        p,
                                                                                                                        StringArgumentType
                                                                                                                                .getString(
                                                                                                                                        ctx,
                                                                                                                                        "name"),
                                                                                                                        true)))))
                                        .then(
                                                Commands.literal("제거")
                                                        .then(
                                                                Commands.argument(
                                                                                "name",
                                                                                StringArgumentType
                                                                                        .word())
                                                                        .executes(
                                                                                ctx ->
                                                                                        player(
                                                                                                ctx,
                                                                                                p ->
                                                                                                        housing
                                                                                                                .member(
                                                                                                                        p,
                                                                                                                        StringArgumentType
                                                                                                                                .getString(
                                                                                                                                        ctx,
                                                                                                                                        "name"),
                                                                                                                        false))))))
                        .build(),
                "개인 부두 (하우징)",
                List.of("home"));
        commands.register(
                Commands.literal("선박")
                        .requires(src -> src.getSender().hasPermission("harbor.world.ship"))
                        .executes(ctx -> player(ctx, p -> ships.summon(p, -1)))
                        .then(
                                Commands.literal("소환")
                                        .executes(ctx -> player(ctx, p -> ships.summon(p, -1))))
                        .then(
                                Commands.literal("회수")
                                        .executes(
                                                ctx ->
                                                        player(
                                                                ctx,
                                                                p ->
                                                                        kit.send(
                                                                                p,
                                                                                ships.recallOwned(
                                                                                                p
                                                                                                        .getUniqueId())
                                                                                        ? "world.ship.recalled"
                                                                                        : "world.ship.none_afloat"))))
                        .then(Commands.literal("화물").executes(ctx -> player(ctx, ships::openCargo)))
                        .then(Commands.literal("정보").executes(ctx -> player(ctx, this::shipInfo)))
                        .build(),
                "선박 소환·회수",
                List.of("ship"));
        commands.register(
                Commands.literal("조선소")
                        .requires(src -> src.getSender().hasPermission("harbor.world.ship"))
                        .executes(ctx -> player(ctx, this::yard))
                        .build(),
                "조선소 (선박 구매·업그레이드·수리)",
                List.of("shipyard"));
        commands.register(
                Commands.literal("비행선")
                        .requires(src -> src.getSender().hasPermission("harbor.world.travel"))
                        .executes(ctx -> player(ctx, p -> rides.board(p, null)))
                        .then(
                                regionArg()
                                        .executes(
                                                ctx ->
                                                        player(
                                                                ctx,
                                                                p ->
                                                                        rides.board(
                                                                                p,
                                                                                region(ctx)
                                                                                        .orElse(
                                                                                                null)))))
                        .build(),
                "항구 비행선 노선",
                List.of("airship"));
        commands.register(
                Commands.literal("노틸러스")
                        .requires(src -> src.getSender().hasPermission("harbor.world.travel"))
                        .executes(ctx -> player(ctx, rides::rent))
                        .build(),
                "심해 탈것 대여",
                List.of("nautilus"));
    }

    /** 조선소: 조선소 시설 8블록 안 (우회 권한이면 어디서나). */
    public void yard(Player player) {
        if (!player.hasPermission(RegionManager.BYPASS)
                && !nearStation(player, RegionExt.StationType.SHIPYARD)) {
            kit.send(player, "world.yard.far");
            return;
        }
        ships.openYard(player);
    }

    private boolean nearStation(Player player, RegionExt.StationType type) {
        Location at = WorldKit.loc(player);
        String world = kit.worldId(at);
        for (WorldIndex.Entry e : kit.index().regions()) {
            if (!e.def().world().equals(world)) {
                continue;
            }
            for (RegionExt.Station s : e.ext().stations()) {
                if (s.type() == type
                        && at.distanceSquared(
                                        new Location(
                                                at.getWorld(), s.at().x(), s.at().y(), s.at().z()))
                                <= 64) {
                    return true;
                }
            }
        }
        return false;
    }

    public void chartDialog(Player player, @Nullable RegionId only) {
        List<Component> body = new ArrayList<>();
        for (Map.Entry<RegionId, ChartService.ChartProgress> e :
                charts.progress(player.getUniqueId()).entrySet()) {
            if (only != null && !only.equals(e.getKey())) {
                continue;
            }
            Optional<WorldIndex.Entry> region = kit.index().get(e.getKey());
            if (region.isEmpty()) {
                continue;
            }
            boolean visited = regions.hasVisited(player.getUniqueId(), e.getKey());
            body.add(
                    kit.api()
                            .messages()
                            .get(
                                    visited ? "world.chart.line" : "world.chart.line_unvisited",
                                    Placeholder.parsed("name", region.get().def().name()),
                                    Placeholder.unparsed(
                                            "percent",
                                            String.format(
                                                    Locale.ROOT,
                                                    "%.1f",
                                                    e.getValue().ratio() * 100)),
                                    Placeholder.unparsed(
                                            "steps", String.valueOf(e.getValue().claimedSteps()))));
        }
        kit.api().dialogs().notice(player, kit.api().messages().get("world.chart.title"), body);
    }

    private void chartMap(Player player, CommandContext<CommandSourceStack> ctx) {
        Optional<RegionId> region = region(ctx);
        if (region.isEmpty() || !regions.hasVisited(player.getUniqueId(), region.get())) {
            kit.send(player, "world.chart.not_visited");
            return;
        }
        charts.chartItem(player, region.get())
                .ifPresentOrElse(
                        item -> kit.give(player, item),
                        () -> kit.send(player, "world.chart.no_map"));
    }

    private void shipInfo(Player player) {
        var list = ships.ships(player.getUniqueId());
        if (list.isEmpty()) {
            kit.send(player, "world.ship.none");
            return;
        }
        for (var s : list) {
            kit.send(
                    player,
                    "world.ship.info",
                    Placeholder.unparsed("name", s.name()),
                    Placeholder.unparsed("state", s.state().name()),
                    Placeholder.unparsed("durability", String.valueOf(s.durability())),
                    Placeholder.unparsed("max", String.valueOf(s.maxDurability())),
                    Placeholder.unparsed(
                            "speed",
                            String.valueOf(
                                    s.level(wiki.creeper.harbor.world.api.UpgradePath.SPEED))),
                    Placeholder.unparsed(
                            "cargo",
                            String.valueOf(
                                    s.level(wiki.creeper.harbor.world.api.UpgradePath.CARGO))),
                    Placeholder.unparsed(
                            "hull",
                            String.valueOf(
                                    s.level(wiki.creeper.harbor.world.api.UpgradePath.HULL))));
        }
    }

    // ------------------------------------------------------------------ 관리 명령

    /** /harbor world … (권한 harbor.admin.world는 Core가 건다, 하위는 harbor.admin.world.&lt;명령&gt;). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("world")
                .then(
                        Commands.literal("region")
                                .requires(perm("region"))
                                .then(
                                        Commands.literal("info")
                                                .executes(ctx -> player(ctx, this::regionInfo)))
                                .then(Commands.literal("list").executes(this::regionList)))
                .then(
                        Commands.literal("chart")
                                .requires(perm("chart"))
                                .then(
                                        Commands.literal("set")
                                                .then(
                                                        target().then(
                                                                        regionArg()
                                                                                .then(
                                                                                        Commands
                                                                                                .argument(
                                                                                                        "percent",
                                                                                                        DoubleArgumentType
                                                                                                                .doubleArg(
                                                                                                                        0,
                                                                                                                        100))
                                                                                                .executes(
                                                                                                        ctx -> {
                                                                                                            Player
                                                                                                                    p =
                                                                                                                            targetPlayer(
                                                                                                                                    ctx);
                                                                                                            boolean
                                                                                                                    ok =
                                                                                                                            region(
                                                                                                                                            ctx)
                                                                                                                                    .map(
                                                                                                                                            r ->
                                                                                                                                                    charts
                                                                                                                                                            .adminSet(
                                                                                                                                                                    p,
                                                                                                                                                                    r,
                                                                                                                                                                    DoubleArgumentType
                                                                                                                                                                                    .getDouble(
                                                                                                                                                                                            ctx,
                                                                                                                                                                                            "percent")
                                                                                                                                                                            / 100))
                                                                                                                                    .orElse(
                                                                                                                                            false);
                                                                                                            return done(
                                                                                                                    ctx,
                                                                                                                    ok);
                                                                                                        }))))))
                .then(
                        Commands.literal("ship")
                                .requires(perm("ship"))
                                .then(
                                        Commands.literal("give")
                                                .then(
                                                        target().then(
                                                                        Commands.argument(
                                                                                        "ship",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        (ctx,
                                                                                                b) -> {
                                                                                            kit.api()
                                                                                                    .content()
                                                                                                    .snapshot()
                                                                                                    .all(
                                                                                                            ShipDef
                                                                                                                    .TYPE)
                                                                                                    .keySet()
                                                                                                    .forEach(
                                                                                                            id ->
                                                                                                                    b
                                                                                                                            .suggest(
                                                                                                                                    id
                                                                                                                                            .name()));
                                                                                            return b
                                                                                                    .buildFuture();
                                                                                        })
                                                                                .executes(
                                                                                        ctx -> {
                                                                                            Player
                                                                                                    p =
                                                                                                            targetPlayer(
                                                                                                                    ctx);
                                                                                            Optional<
                                                                                                            ShipDef>
                                                                                                    def =
                                                                                                            HarborId
                                                                                                                    .tryParse(
                                                                                                                            "ship/"
                                                                                                                                    + StringArgumentType
                                                                                                                                            .getString(
                                                                                                                                                    ctx,
                                                                                                                                                    "ship"))
                                                                                                                    .flatMap(
                                                                                                                            ships
                                                                                                                                    ::def);
                                                                                            if (def
                                                                                                    .isEmpty()) {
                                                                                                return done(
                                                                                                        ctx,
                                                                                                        false);
                                                                                            }
                                                                                            var _ =
                                                                                                    ships
                                                                                                            .adminGive(
                                                                                                                    p,
                                                                                                                    def
                                                                                                                            .get());
                                                                                            return done(
                                                                                                    ctx,
                                                                                                    true);
                                                                                        }))))
                                .then(
                                        Commands.literal("repair")
                                                .then(
                                                        target().executes(
                                                                        ctx -> {
                                                                            ships.adminRepair(
                                                                                    targetPlayer(
                                                                                            ctx));
                                                                            return done(ctx, true);
                                                                        })))
                                .then(
                                        Commands.literal("recall")
                                                .then(
                                                        target().executes(
                                                                        ctx ->
                                                                                done(
                                                                                        ctx,
                                                                                        ships
                                                                                                .recallOwned(
                                                                                                        targetPlayer(
                                                                                                                        ctx)
                                                                                                                .getUniqueId()))))))
                .then(
                        Commands.literal("event")
                                .requires(perm("event"))
                                .then(Commands.literal("list").executes(this::eventList))
                                .then(
                                        Commands.literal("start")
                                                .then(
                                                        eventArg()
                                                                .executes(ctx -> eventStart(ctx, 0))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "minutes",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        1,
                                                                                                        7
                                                                                                                * 24
                                                                                                                * 60))
                                                                                .executes(
                                                                                        ctx ->
                                                                                                eventStart(
                                                                                                        ctx,
                                                                                                        IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        ctx,
                                                                                                                        "minutes"))))))
                                .then(
                                        Commands.literal("stop")
                                                .then(
                                                        Commands.argument(
                                                                        "run",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        (ctx, b) -> {
                                                                            events.all()
                                                                                    .forEach(
                                                                                            e ->
                                                                                                    b
                                                                                                            .suggest(
                                                                                                                    e
                                                                                                                            .runId()));
                                                                            return b.buildFuture();
                                                                        })
                                                                .executes(
                                                                        ctx -> {
                                                                            CommandSender sender =
                                                                                    ctx.getSource()
                                                                                            .getSender();
                                                                            var _ =
                                                                                    scheduler
                                                                                            .stop(
                                                                                                    StringArgumentType
                                                                                                            .getString(
                                                                                                                    ctx,
                                                                                                                    "run"))
                                                                                            .thenAccept(
                                                                                                    ok ->
                                                                                                            kit.api()
                                                                                                                    .messages()
                                                                                                                    .send(
                                                                                                                            sender,
                                                                                                                            ok
                                                                                                                                    ? "world.admin.done"
                                                                                                                                    : "world.admin.failed"));
                                                                            return Command
                                                                                    .SINGLE_SUCCESS;
                                                                        }))))
                .then(
                        Commands.literal("spot")
                                .requires(perm("spot"))
                                .then(Commands.literal("list").executes(this::spotList))
                                .then(
                                        Commands.literal("move")
                                                .then(
                                                        regionArg()
                                                                .executes(
                                                                        ctx ->
                                                                                done(
                                                                                        ctx,
                                                                                        region(ctx)
                                                                                                        .map(
                                                                                                                spots
                                                                                                                        ::moveNow)
                                                                                                        .orElse(
                                                                                                                0)
                                                                                                > 0)))))
                .then(
                        Commands.literal("plot")
                                .requires(perm("plot"))
                                .then(
                                        Commands.literal("info")
                                                .executes(ctx -> player(ctx, this::plotInfo)))
                                .then(
                                        Commands.literal("tp")
                                                .then(
                                                        target().executes(
                                                                        ctx -> {
                                                                            Player admin =
                                                                                    (Player)
                                                                                            ctx.getSource()
                                                                                                    .getSender();
                                                                            var _ =
                                                                                    housing.loadPlot(
                                                                                                    targetPlayer(
                                                                                                                    ctx)
                                                                                                            .getUniqueId())
                                                                                            .thenAccept(
                                                                                                    p ->
                                                                                                            p
                                                                                                                    .ifPresentOrElse(
                                                                                                                            plot ->
                                                                                                                                    housing
                                                                                                                                            .visitAsAdmin(
                                                                                                                                                    admin,
                                                                                                                                                    plot),
                                                                                                                            () ->
                                                                                                                                    kit
                                                                                                                                            .send(
                                                                                                                                                    admin,
                                                                                                                                                    "world.housing.no_plot")));
                                                                            return Command
                                                                                    .SINGLE_SUCCESS;
                                                                        })))
                                .then(
                                        Commands.literal("reset")
                                                .then(
                                                        target().executes(
                                                                        ctx -> {
                                                                            CommandSender sender =
                                                                                    ctx.getSource()
                                                                                            .getSender();
                                                                            var _ =
                                                                                    housing.adminReset(
                                                                                                    targetPlayer(
                                                                                                                    ctx)
                                                                                                            .getUniqueId())
                                                                                            .thenAccept(
                                                                                                    ok ->
                                                                                                            kit.api()
                                                                                                                    .messages()
                                                                                                                    .send(
                                                                                                                            sender,
                                                                                                                            ok
                                                                                                                                    ? "world.admin.done"
                                                                                                                                    : "world.admin.failed"));
                                                                            return Command
                                                                                    .SINGLE_SUCCESS;
                                                                        })))
                                .then(
                                        Commands.literal("transfer")
                                                .then(
                                                        Commands.argument(
                                                                        "from",
                                                                        ArgumentTypes.player())
                                                                .then(
                                                                        Commands.argument(
                                                                                        "to",
                                                                                        ArgumentTypes
                                                                                                .player())
                                                                                .executes(
                                                                                        ctx -> {
                                                                                            CommandSender
                                                                                                    sender =
                                                                                                            ctx.getSource()
                                                                                                                    .getSender();
                                                                                            Player
                                                                                                    from =
                                                                                                            ctx.getArgument(
                                                                                                                            "from",
                                                                                                                            PlayerSelectorArgumentResolver
                                                                                                                                    .class)
                                                                                                                    .resolve(
                                                                                                                            ctx
                                                                                                                                    .getSource())
                                                                                                                    .getFirst();
                                                                                            Player
                                                                                                    to =
                                                                                                            ctx.getArgument(
                                                                                                                            "to",
                                                                                                                            PlayerSelectorArgumentResolver
                                                                                                                                    .class)
                                                                                                                    .resolve(
                                                                                                                            ctx
                                                                                                                                    .getSource())
                                                                                                                    .getFirst();
                                                                                            var _ =
                                                                                                    housing.adminTransfer(
                                                                                                                    from
                                                                                                                            .getUniqueId(),
                                                                                                                    to
                                                                                                                            .getUniqueId())
                                                                                                            .thenAccept(
                                                                                                                    ok ->
                                                                                                                            kit.api()
                                                                                                                                    .messages()
                                                                                                                                    .send(
                                                                                                                                            sender,
                                                                                                                                            ok
                                                                                                                                                    ? "world.admin.done"
                                                                                                                                                    : "world.admin.failed"));
                                                                                            return Command
                                                                                                    .SINGLE_SUCCESS;
                                                                                        })))));
    }

    private static java.util.function.Predicate<CommandSourceStack> perm(String sub) {
        return src -> src.getSender().hasPermission("harbor.admin.world." + sub);
    }

    private void regionInfo(Player player) {
        Location at = WorldKit.loc(player);
        Optional<WorldIndex.Entry> region = regions.entryAt(at);
        var key = regions.fishingKey(at);
        kit.send(
                player,
                "world.admin.region_info",
                Placeholder.unparsed("region", region.map(r -> r.id().toString()).orElse("-")),
                Placeholder.unparsed("biome", key.map(k -> k.biomeKey()).orElse("-")),
                Placeholder.unparsed("areas", String.join(", ", regions.areasAt(at))),
                Placeholder.unparsed(
                        "school", String.valueOf(key.map(k -> k.school()).orElse(false))),
                Placeholder.unparsed(
                        "occupancy",
                        region.map(r -> String.valueOf(regions.occupancy(r.id()))).orElse("0")));
    }

    private int regionList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        for (WorldIndex.Entry e : kit.index().regions()) {
            kit.api()
                    .messages()
                    .send(
                            sender,
                            "world.admin.region_line",
                            Placeholder.unparsed("order", String.valueOf(e.ext().order())),
                            Placeholder.unparsed("region", e.id().toString()),
                            Placeholder.parsed("name", e.def().name()),
                            Placeholder.unparsed("world", e.def().world()),
                            Placeholder.unparsed(
                                    "occupancy", String.valueOf(regions.occupancy(e.id()))));
        }
        kit.index()
                .stats()
                .forEach(
                        (world, s) ->
                                kit.api()
                                        .messages()
                                        .send(
                                                sender,
                                                "world.admin.index_line",
                                                Placeholder.unparsed("world", world),
                                                Placeholder.unparsed(
                                                        "chunks", String.valueOf(s[0])),
                                                Placeholder.unparsed(
                                                        "mixed", String.valueOf(s[1]))));
        return Command.SINGLE_SUCCESS;
    }

    private int eventList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        for (ActiveSeaEvent e : events.all()) {
            kit.api()
                    .messages()
                    .send(
                            sender,
                            "world.admin.event_line",
                            Placeholder.unparsed("run", e.runId()),
                            Placeholder.parsed("name", e.name()),
                            Placeholder.unparsed("phase", e.phase().name()),
                            Placeholder.unparsed(
                                    "region",
                                    e.region() == null ? "-" : String.valueOf(e.region())),
                            Placeholder.unparsed("ends", e.endsAt().toString()));
        }
        if (events.all().isEmpty()) {
            kit.api().messages().send(sender, "world.admin.event_none");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int eventStart(CommandContext<CommandSourceStack> ctx, int minutes) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<SeaEventDef> def =
                HarborId.tryParse("event/" + StringArgumentType.getString(ctx, "event"))
                        .flatMap(id -> kit.api().content().snapshot().get(SeaEventDef.TYPE, id));
        if (def.isEmpty()) {
            return done(ctx, false);
        }
        var _ =
                scheduler
                        .startNow(def.get(), null, minutes)
                        .thenAccept(
                                run ->
                                        kit.api()
                                                .messages()
                                                .send(
                                                        sender,
                                                        run.isPresent()
                                                                ? "world.admin.event_started"
                                                                : "world.admin.failed",
                                                        Placeholder.unparsed(
                                                                "run", run.orElse("-"))));
        return Command.SINGLE_SUCCESS;
    }

    private int spotList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        for (FishingSpot s : spots.all()) {
            kit.api()
                    .messages()
                    .send(
                            sender,
                            "world.admin.spot_line",
                            Placeholder.unparsed("id", s.id()),
                            Placeholder.unparsed("x", String.valueOf((int) s.x())),
                            Placeholder.unparsed("z", String.valueOf((int) s.z())),
                            Placeholder.unparsed("hidden", String.valueOf(s.hidden())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void plotInfo(Player player) {
        Optional<PlotInfo> plot = housing.plotAt(WorldKit.loc(player));
        if (plot.isEmpty()) {
            kit.send(player, "world.housing.not_in_plot");
            return;
        }
        PlotInfo p = plot.get();
        kit.send(
                player,
                "world.admin.plot_info",
                Placeholder.unparsed("id", String.valueOf(p.plotId())),
                Placeholder.unparsed("owner", p.ownerName()),
                Placeholder.unparsed("size", String.valueOf(p.size())),
                Placeholder.unparsed("policy", p.visitPolicy().key()),
                Placeholder.unparsed("likes", String.valueOf(p.likes())),
                Placeholder.unparsed(
                        "anchors", String.valueOf(housing.displayAnchors(p.plotId()).size())));
    }

    // ------------------------------------------------------------------ 도우미

    private RequiredArgumentBuilder<CommandSourceStack, String> regionArg() {
        return Commands.argument("region", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            kit.index().regions().forEach(e -> b.suggest(e.id().id().name()));
                            return b.buildFuture();
                        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> eventArg() {
        return Commands.argument("event", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            kit.api()
                                    .content()
                                    .snapshot()
                                    .all(SeaEventDef.TYPE)
                                    .keySet()
                                    .forEach(id -> b.suggest(id.name()));
                            return b.buildFuture();
                        });
    }

    private Optional<RegionId> region(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "region");
        return HarborId.tryParse(raw.contains("/") ? raw : "region/" + raw)
                .filter(id -> id.hasPrefix(RegionId.PREFIX))
                .map(RegionId::new)
                .filter(id -> kit.index().get(id).isPresent());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver>
            target() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    private static Player targetPlayer(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }

    private int done(CommandContext<CommandSourceStack> ctx, boolean ok) {
        kit.api()
                .messages()
                .send(ctx.getSource().getSender(), ok ? "world.admin.done" : "world.admin.failed");
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private static int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            action.accept(p);
            return Command.SINGLE_SUCCESS;
        }
        return 0;
    }
}
