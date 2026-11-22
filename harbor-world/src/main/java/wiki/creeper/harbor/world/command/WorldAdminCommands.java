package wiki.creeper.harbor.world.command;

import static wiki.creeper.harbor.world.command.CommandSupport.perm;
import static wiki.creeper.harbor.world.command.CommandSupport.player;
import static wiki.creeper.harbor.world.command.CommandSupport.target;
import static wiki.creeper.harbor.world.command.CommandSupport.targetPlayer;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.chart.ChartManager;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.housing.HousingManager;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;
import wiki.creeper.harbor.world.sea.SeaEventManager;
import wiki.creeper.harbor.world.sea.SeaEventScheduler;
import wiki.creeper.harbor.world.ship.ShipManager;
import wiki.creeper.harbor.world.spot.SpotManager;

/** /harbor world … admin subcommand tree (split out of {@link WorldCommands}). */
final class WorldAdminCommands {

    private final WorldKit kit;
    private final RegionManager regions;
    private final ChartManager charts;
    private final ShipManager ships;
    private final HousingManager housing;
    private final SeaEventManager events;
    private final SeaEventScheduler scheduler;
    private final SpotManager spots;
    private final CommandSupport support;

    WorldAdminCommands(
            WorldKit kit,
            RegionManager regions,
            ChartManager charts,
            ShipManager ships,
            HousingManager housing,
            SeaEventManager events,
            SeaEventScheduler scheduler,
            SpotManager spots,
            CommandSupport support) {
        this.kit = kit;
        this.regions = regions;
        this.charts = charts;
        this.ships = ships;
        this.housing = housing;
        this.events = events;
        this.scheduler = scheduler;
        this.spots = spots;
        this.support = support;
    }

    /**
     * /harbor world … (Core applies the harbor.admin.world permission; subcommands use
     * harbor.admin.world.&lt;command&gt;).
     */
    LiteralArgumentBuilder<CommandSourceStack> build() {
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

    private RequiredArgumentBuilder<CommandSourceStack, String> regionArg() {
        return support.regionArg();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> eventArg() {
        return support.eventArg();
    }

    private Optional<RegionId> region(CommandContext<CommandSourceStack> ctx) {
        return support.region(ctx);
    }

    private int done(CommandContext<CommandSourceStack> ctx, boolean ok) {
        return support.done(ctx, ok);
    }
}
