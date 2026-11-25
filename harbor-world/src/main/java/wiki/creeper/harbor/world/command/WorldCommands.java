package wiki.creeper.harbor.world.command;

import static wiki.creeper.harbor.world.command.CommandSupport.player;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ChartService;
import wiki.creeper.harbor.world.chart.ChartManager;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.housing.DecorManager;
import wiki.creeper.harbor.world.housing.HousingManager;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;
import wiki.creeper.harbor.world.ride.RideManager;
import wiki.creeper.harbor.world.sea.SeaEventManager;
import wiki.creeper.harbor.world.sea.SeaEventScheduler;
import wiki.creeper.harbor.world.ship.ShipManager;
import wiki.creeper.harbor.world.spot.SpotManager;

/**
 * /해도 · /부두 · /선박 · /조선소 · /비행선 · /노틸러스 (chart, dock, ship, shipyard, airship, nautilus), /harbor
 * world … (docs 20 8.5).
 */
public final class WorldCommands {

    private final WorldKit kit;
    private final RegionManager regions;
    private final ChartManager charts;
    private final ShipManager ships;
    private final HousingManager housing;
    private final DecorManager decor;
    private final RideManager rides;
    private final CommandSupport support;
    private final WorldAdminCommands admin;

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
        this.support = new CommandSupport(kit);
        this.admin =
                new WorldAdminCommands(
                        kit, regions, charts, ships, housing, events, scheduler, spots, support);
    }

    // ------------------------------------------------------------------ player commands

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
                List.of());
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
                        .then(Commands.literal("나가기").executes(ctx -> player(ctx, housing::leave)))
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
                List.of());
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
                List.of());
        commands.register(
                Commands.literal("조선소")
                        .requires(src -> src.getSender().hasPermission("harbor.world.ship"))
                        .executes(ctx -> player(ctx, this::yard))
                        .build(),
                "조선소 (선박 구매·업그레이드·수리)",
                List.of());
        commands.register(
                Commands.literal("비행선")
                        .requires(src -> src.getSender().hasPermission("harbor.world.travel"))
                        .executes(ctx -> player(ctx, p -> rides.boardOrChoose(p, null)))
                        .then(
                                regionArg()
                                        .executes(
                                                ctx ->
                                                        player(
                                                                ctx,
                                                                p ->
                                                                        rides.boardOrChoose(
                                                                                p,
                                                                                region(ctx)
                                                                                        .orElse(
                                                                                                null)))))
                        .build(),
                "항구 비행선 노선",
                List.of());
        commands.register(
                Commands.literal("노틸러스")
                        .requires(src -> src.getSender().hasPermission("harbor.world.travel"))
                        .executes(ctx -> player(ctx, rides::rent))
                        .build(),
                "심해 탈것 대여",
                List.of());
    }

    /** Shipyard: within 8 blocks of a shipyard facility (anywhere with the bypass permission). */
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

    // ------------------------------------------------------------------ admin commands

    /**
     * /harbor world … (Core applies the harbor.admin.world permission; subcommands use
     * harbor.admin.world.&lt;command&gt;).
     */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return admin.build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> regionArg() {
        return support.regionArg();
    }

    private Optional<RegionId> region(CommandContext<CommandSourceStack> ctx) {
        return support.region(ctx);
    }
}
