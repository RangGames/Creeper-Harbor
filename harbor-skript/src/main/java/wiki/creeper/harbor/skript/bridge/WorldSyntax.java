package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.lang.Expression;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.ChartService;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.FishingSpotService;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.api.RegionInfo;
import wiki.creeper.harbor.world.api.RegionService;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.api.ShipInfo;
import wiki.creeper.harbor.world.api.ShipService;
import wiki.creeper.harbor.world.api.TreasureService;
import wiki.creeper.harbor.world.api.UnlockStatus;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.api.event.ChartProgressEvent;
import wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent;
import wiki.creeper.harbor.world.api.event.FishingSpotMovedEvent;
import wiki.creeper.harbor.world.api.event.PlotLikedEvent;
import wiki.creeper.harbor.world.api.event.PlotLoadedEvent;
import wiki.creeper.harbor.world.api.event.PlotUnloadedEvent;
import wiki.creeper.harbor.world.api.event.RegionBlockedEvent;
import wiki.creeper.harbor.world.api.event.RegionEnterEvent;
import wiki.creeper.harbor.world.api.event.RegionFirstVisitEvent;
import wiki.creeper.harbor.world.api.event.RegionLeaveEvent;
import wiki.creeper.harbor.world.api.event.SeaEventEndEvent;
import wiki.creeper.harbor.world.api.event.SeaEventStartEvent;
import wiki.creeper.harbor.world.api.event.SeaEventWarnEvent;
import wiki.creeper.harbor.world.api.event.ShipSummonEvent;
import wiki.creeper.harbor.world.api.event.ShipWreckedEvent;
import wiki.creeper.harbor.world.api.event.TreasureClaimedEvent;

/**
 * When Harbor-World is enabled: regions (region at a location, visits, entry conditions, admin
 * travel), ships (owned, afloat, boarded), housing plots (plot at a location, owner, members,
 * permission checks), treasure maps, sea charts, fish school spots, sea events, and all world api
 * events. All calls are World main-thread lookups (online players and loaded plots only).
 */
public final class WorldSyntax {

    private WorldSyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<RegionId> region(String raw) {
        try {
            return Ids.parse(raw, RegionId.PREFIX).map(RegionId::new);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<RegionId> regionArg(Event e, Expression<?>[] x, int index) {
        return Args.string(e, x, index).flatMap(WorldSyntax::region);
    }

    private static @Nullable Location loc(Event e, Expression<?>[] x, int index) {
        return Args.single(e, x, index) instanceof Location l ? l : null;
    }

    private static @Nullable OfflinePlayer offlineArg(Event e, Expression<?>[] x, int index) {
        return Args.single(e, x, index) instanceof OfflinePlayer p ? p : null;
    }

    private static OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    public static void register(Registrar r) {
        regions(r);
        ships(r);
        housing(r);
        treasureAndCharts(r);
        seaEvents(r);
        events(r);
    }

    // ------------------------------------------------------------------ regions

    private static void regions(Registrar r) {
        r.text(
                Spec.single("harbor region ids"),
                (e, x) ->
                        service(RegionService.class)
                                .map(s -> s.regions().stream().map(i -> i.id().toString()).toList())
                                .orElse(List.of()),
                "[all] [the] harbor regions",
                "[all] [the] harbor region ids");
        r.text(
                Spec.single("harbor region at"),
                (e, x) -> {
                    Location l = loc(e, x, 0);
                    Optional<RegionService> s = service(RegionService.class);
                    if (l == null || s.isEmpty()) {
                        return List.of();
                    }
                    return Args.one(s.get().regionAt(l).map(i -> i.id().toString()).orElse(null));
                },
                "[the] harbor region at %location%");
        r.text(
                Spec.single("harbor areas at"),
                (e, x) -> {
                    Location l = loc(e, x, 0);
                    Optional<RegionService> s = service(RegionService.class);
                    return l == null || s.isEmpty() ? List.of() : s.get().areasAt(l);
                },
                "[the] harbor areas at %location%");
        r.text(
                Spec.of("harbor current region"),
                (e, x) ->
                        service(RegionService.class)
                                .map(
                                        s ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                s.current(p.getUniqueId())
                                                                        .map(RegionId::toString)
                                                                        .orElse(null)))
                                .orElse(List.of()),
                "[the] [current] harbor region of %players%",
                "%players%'[s] [current] harbor region");
        r.text(
                Spec.single("harbor region name"),
                (e, x) -> regionInfo(e, x, 0).map(i -> List.of(i.name())).orElse(List.of()),
                "[the] harbor region name of %string%");
        r.text(
                Spec.single("harbor region world"),
                (e, x) -> regionInfo(e, x, 0).map(i -> List.of(i.world())).orElse(List.of()),
                "[the] harbor region world of %string%");
        r.text(
                Spec.single("harbor region server"),
                (e, x) -> regionInfo(e, x, 0).map(i -> Args.one(i.server())).orElse(List.of()),
                "[the] harbor region server of %string%");
        r.text(
                Spec.single("harbor region region areas"),
                (e, x) -> regionInfo(e, x, 0).map(RegionInfo::areas).orElse(List.of()),
                "[the] harbor region areas of %string%");
        r.number(
                Spec.single("harbor region order"),
                (e, x) ->
                        regionInfo(e, x, 0).map(i -> List.<Number>of(i.order())).orElse(List.of()),
                "[the] harbor region order of %string%");
        r.number(
                Spec.single("harbor region recommended level"),
                (e, x) ->
                        regionInfo(e, x, 0)
                                .map(i -> List.<Number>of(i.recLevel()))
                                .orElse(List.of()),
                "[the] harbor region recommended level of %string%");
        r.number(
                Spec.single("harbor region occupancy"),
                (e, x) -> {
                    Optional<RegionId> id = regionArg(e, x, 0);
                    Optional<RegionService> s = service(RegionService.class);
                    return id.isPresent() && s.isPresent()
                            ? List.of(s.get().occupancy(id.get()))
                            : List.of();
                },
                "[the] harbor region (occupancy|player count) of %string%");
        r.text(
                Spec.single("harbor visited regions"),
                (e, x) -> {
                    Optional<RegionService> s = service(RegionService.class);
                    if (s.isEmpty() || !(Args.single(e, x, 0) instanceof Player p)) {
                        return List.of();
                    }
                    return s.get().visited(p.getUniqueId()).stream()
                            .map(RegionId::toString)
                            .sorted()
                            .toList();
                },
                "[the] visited harbor regions of %player%");
        r.condition(
                Spec.of("harbor region visited"),
                (e, x) -> {
                    Optional<RegionId> id = regionArg(e, x, 1);
                    Optional<RegionService> s = service(RegionService.class);
                    return id.isPresent()
                            && s.isPresent()
                            && Args.all(e, x, p -> s.get().hasVisited(p.getUniqueId(), id.get()));
                },
                "%players% (has|have) visited harbor region %string%",
                "%players% (hasn't|has not|haven't|have not) visited harbor region %string%");
        r.condition(
                Spec.of("harbor region unlocked"),
                (e, x) -> {
                    Optional<RegionId> id = regionArg(e, x, 1);
                    Optional<RegionService> s = service(RegionService.class);
                    return id.isPresent()
                            && s.isPresent()
                            && Args.all(e, x, p -> s.get().unlockStatus(p, id.get()).allowed());
                },
                "%players% can enter harbor region %string%",
                "%players% (can't|cannot|can not) enter harbor region %string%");
        r.text(
                Spec.single("harbor region missing requirements"),
                (e, x) -> {
                    Optional<RegionId> id = regionArg(e, x, 1);
                    Optional<RegionService> s = service(RegionService.class);
                    if (id.isEmpty()
                            || s.isEmpty()
                            || !(Args.single(e, x, 0) instanceof Player p)) {
                        return List.of();
                    }
                    List<String> out = new ArrayList<>();
                    for (UnlockStatus.Requirement req :
                            s.get().unlockStatus(p, id.get()).missing()) {
                        out.add(
                                key(req.kind())
                                        + ":"
                                        + req.detail()
                                        + ":"
                                        + req.have()
                                        + "/"
                                        + req.need());
                    }
                    return out;
                },
                "[the] missing harbor region requirements of %player% for %string%");
        r.text(
                Spec.of("harbor highest unlocked region"),
                (e, x) ->
                        service(RegionService.class)
                                .map(
                                        s ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p -> s.highestUnlocked(p).id().toString()))
                                .orElse(List.of()),
                "[the] highest unlocked harbor region of %players%");
        r.effect(
                Spec.of("admin harbor region travel"),
                (e, x) -> {
                    Optional<RegionId> id = regionArg(e, x, 1);
                    Optional<RegionService> s = service(RegionService.class);
                    if (id.isEmpty() || s.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 0)) {
                        s.get().travel(p, id.get(), "skript");
                    }
                },
                "harbor admin travel %players% to region %string%");
    }

    private static Optional<RegionInfo> regionInfo(Event e, Expression<?>[] x, int index) {
        Optional<RegionId> id = regionArg(e, x, index);
        Optional<RegionService> s = service(RegionService.class);
        return id.isPresent() && s.isPresent() ? s.get().region(id.get()) : Optional.empty();
    }

    // ------------------------------------------------------------------ ships

    private static void ships(Registrar r) {
        r.number(
                Spec.single("harbor ship ids"),
                (e, x) -> {
                    Optional<ShipService> s = service(ShipService.class);
                    if (s.isEmpty() || !(Args.single(e, x, 0) instanceof Player p)) {
                        return List.of();
                    }
                    return s.get().ships(p.getUniqueId()).stream()
                            .<Number>map(ShipInfo::shipId)
                            .toList();
                },
                "[the] harbor ship ids of %player%");
        r.text(
                Spec.single("harbor ship types"),
                (e, x) -> {
                    Optional<ShipService> s = service(ShipService.class);
                    if (s.isEmpty() || !(Args.single(e, x, 0) instanceof Player p)) {
                        return List.of();
                    }
                    return s.get().ships(p.getUniqueId()).stream()
                            .map(i -> i.type().toString())
                            .toList();
                },
                "[the] harbor ship types of %player%");
        for (String which : List.of("afloat", "boarded")) {
            BiFunction<ShipService, Player, Optional<ShipInfo>> pick =
                    which.equals("afloat")
                            ? (s, p) -> s.afloat(p.getUniqueId())
                            : ShipService::aboard;
            shipText(r, which, "type", pick, i -> i.type().toString());
            shipText(r, which, "name", pick, ShipInfo::name);
            shipText(r, which, "state", pick, i -> key(i.state()));
            shipNumber(r, which, "id", pick, ShipInfo::shipId);
            shipNumber(r, which, "durability", pick, ShipInfo::durability);
            shipNumber(r, which, "max durability", pick, ShipInfo::maxDurability);
            for (UpgradePath path : UpgradePath.values()) {
                shipNumber(r, which, path.key() + " level", pick, i -> i.level(path));
            }
        }
        r.number(
                Spec.of("harbor ship speed multiplier"),
                (e, x) ->
                        service(ShipService.class)
                                .map(s -> Args.<Number>perPlayer(e, x, s::speedMultiplier))
                                .orElse(List.of()),
                "[the] harbor ship speed multiplier of %players%");
        r.condition(
                Spec.of("aboard harbor ship"),
                (e, x) ->
                        service(ShipService.class)
                                .map(s -> Args.all(e, x, p -> s.aboard(p).isPresent()))
                                .orElse(false),
                "%players% (is|are) aboard [a] harbor ship",
                "%players% (isn't|is not|aren't|are not) aboard [a] harbor ship");
        r.condition(
                Spec.of("harbor ship afloat"),
                (e, x) ->
                        service(ShipService.class)
                                .map(
                                        s ->
                                                Args.all(
                                                        e,
                                                        x,
                                                        p -> s.afloat(p.getUniqueId()).isPresent()))
                                .orElse(false),
                "%players% (has|have) [a] harbor ship afloat",
                "%players% (doesn't|does not|don't|do not) have [a] harbor ship afloat");
    }

    private static void shipText(
            Registrar r,
            String which,
            String field,
            BiFunction<ShipService, Player, Optional<ShipInfo>> pick,
            Function<ShipInfo, String> f) {
        r.text(
                Spec.of("harbor " + which + " ship " + field),
                (e, x) ->
                        service(ShipService.class)
                                .map(
                                        s ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p -> pick.apply(s, p).map(f).orElse(null)))
                                .orElse(List.of()),
                "[the] harbor " + which + " ship " + field + " of %players%");
    }

    private static void shipNumber(
            Registrar r,
            String which,
            String field,
            BiFunction<ShipService, Player, Optional<ShipInfo>> pick,
            Function<ShipInfo, Number> f) {
        r.number(
                Spec.of("harbor " + which + " ship " + field),
                (e, x) ->
                        service(ShipService.class)
                                .map(
                                        s ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p -> pick.apply(s, p).map(f).orElse(null)))
                                .orElse(List.of()),
                "[the] harbor " + which + " ship " + field + " of %players%");
    }

    // ------------------------------------------------------------------ housing

    private static void housing(Registrar r) {
        r.condition(
                Spec.single("harbor housing world"),
                (e, x) -> {
                    Location l = loc(e, x, 0);
                    Optional<HousingService> h = service(HousingService.class);
                    return l != null
                            && l.getWorld() != null
                            && h.isPresent()
                            && h.get().isHousingWorld(l.getWorld());
                },
                "%location% is in [a] harbor housing world",
                "%location% (isn't|is not) in [a] harbor housing world");
        for (String form : List.of("at %location%", "of %offlineplayer%")) {
            boolean at = form.startsWith("at");
            Function<Function<PlotInfo, @Nullable String>, Spec.Getter<String>> text =
                    f -> (e, x) -> plot(e, x, at).map(p -> Args.one(f.apply(p))).orElse(List.of());
            Function<Function<PlotInfo, Number>, Spec.Getter<Number>> number =
                    f -> (e, x) -> plot(e, x, at).map(p -> List.of(f.apply(p))).orElse(List.of());
            r.number(
                    Spec.single("harbor plot id"),
                    number.apply(PlotInfo::plotId),
                    "[the] harbor plot id " + form);
            r.number(
                    Spec.single("harbor plot size"),
                    number.apply(PlotInfo::size),
                    "[the] harbor plot size " + form);
            r.number(
                    Spec.single("harbor plot likes"),
                    number.apply(PlotInfo::likes),
                    "[the] harbor plot likes " + form);
            r.text(
                    Spec.single("harbor plot theme"),
                    text.apply(PlotInfo::theme),
                    "[the] harbor plot theme " + form);
            r.text(
                    Spec.single("harbor plot visit policy"),
                    text.apply(p -> p.visitPolicy().key()),
                    "[the] harbor plot visit policy " + form);
            r.player(
                    Spec.single("harbor plot members"),
                    (e, x) ->
                            plot(e, x, at)
                                    .map(
                                            p ->
                                                    p.members().stream()
                                                            .map(WorldSyntax::offline)
                                                            .toList())
                                    .orElse(List.of()),
                    "[the] harbor plot members " + form);
            DomainExpr.of(Location.class, "harbor plot spawn")
                    .single()
                    .get((e, x) -> plot(e, x, at).map(p -> Args.one(spawn(p))).orElse(List.of()))
                    .register(r, "[the] harbor plot spawn " + form);
        }
        r.player(
                Spec.single("harbor plot owner"),
                (e, x) -> plot(e, x, true).map(p -> List.of(offline(p.owner()))).orElse(List.of()),
                "[the] harbor plot owner at %location%");
        r.condition(
                Spec.single("harbor plot member"),
                (e, x) -> {
                    OfflinePlayer who = offlineArg(e, x, 0);
                    Optional<PlotInfo> plot = plotAt(loc(e, x, 1));
                    return who != null
                            && plot.isPresent()
                            && plot.get().isMember(who.getUniqueId());
                },
                "%offlineplayer% is [a] member of [the] harbor plot at %location%",
                "%offlineplayer% (isn't|is not) [a] member of [the] harbor plot at %location%");
        for (PlotPermission perm : PlotPermission.values()) {
            String verb = key(perm).replace('_', ' ');
            r.condition(
                    Spec.of("harbor plot can " + verb),
                    (e, x) -> {
                        Optional<PlotInfo> plot = plotAt(loc(e, x, 1));
                        Optional<HousingService> h = service(HousingService.class);
                        return plot.isPresent()
                                && h.isPresent()
                                && Args.all(e, x, p -> h.get().can(p, plot.get(), perm));
                    },
                    "%players% can " + verb + " [the] harbor plot at %location%",
                    "%players% (can't|cannot|can not) "
                            + verb
                            + " [the] harbor plot at %location%");
        }
    }

    private static Optional<PlotInfo> plotAt(@Nullable Location l) {
        Optional<HousingService> h = service(HousingService.class);
        return l == null || h.isEmpty() ? Optional.empty() : h.get().plotAt(l);
    }

    private static Optional<PlotInfo> plot(Event e, Expression<?>[] x, boolean at) {
        if (at) {
            return plotAt(loc(e, x, 0));
        }
        OfflinePlayer owner = offlineArg(e, x, 0);
        Optional<HousingService> h = service(HousingService.class);
        return owner == null || h.isEmpty()
                ? Optional.empty()
                : h.get().plotOf(owner.getUniqueId());
    }

    private static @Nullable Location spawn(PlotInfo p) {
        World w = Bukkit.getWorld(p.world());
        return w == null
                ? null
                : new Location(w, p.spawnX(), p.spawnY(), p.spawnZ(), p.spawnYaw(), 0);
    }

    // ------------------------------------------------------------------ treasure, charts, spots

    private static void treasureAndCharts(Registrar r) {
        DomainExpr.of(ItemStack.class, "harbor treasure map")
                .single()
                .get(
                        (e, x) -> {
                            Optional<RegionId> id = regionArg(e, x, 0);
                            Optional<TreasureService> t = service(TreasureService.class);
                            if (id.isEmpty()
                                    || t.isEmpty()
                                    || !(Args.single(e, x, 1) instanceof Player p)) {
                                return List.of();
                            }
                            Optional<String> tier = Args.string(e, x, 2);
                            return Args.one(
                                    (tier.isPresent()
                                                    ? t.get().createMap(p, id.get(), tier.get())
                                                    : t.get().createMap(p, id.get()))
                                            .orElse(null));
                        })
                .register(
                        r,
                        "[a] [new] harbor treasure map (of|in|for) region %string% for %player%"
                                + " [with tier %-string%]");
        r.condition(
                Spec.of("is harbor treasure map"),
                (e, x) -> {
                    Optional<TreasureService> t = service(TreasureService.class);
                    if (t.isEmpty() || x.length == 0 || x[0] == null) {
                        return false;
                    }
                    Object[] items = x[0].getArray(e);
                    if (items.length == 0) {
                        return false;
                    }
                    for (Object o : items) {
                        if (!(o instanceof ItemStack s) || !t.get().isTreasureMap(s)) {
                            return false;
                        }
                    }
                    return true;
                },
                "%itemstacks% (is|are) [a] harbor treasure map[s]",
                "%itemstacks% (isn't|is not|aren't|are not) [a] harbor treasure map[s]");

        r.number(
                Spec.of("harbor chart percent"),
                (e, x) -> chart(e, x, c -> c.ratio() * 100),
                "[the] harbor chart percent[age] of %players% (in|for) region %string%");
        r.number(
                Spec.of("harbor chart steps"),
                (e, x) -> chart(e, x, ChartService.ChartProgress::claimedSteps),
                "[the] harbor chart [reward] steps of %players% (in|for) region %string%");
        DomainExpr.of(ItemStack.class, "harbor chart item")
                .single()
                .get(
                        (e, x) -> {
                            Optional<RegionId> id = regionArg(e, x, 0);
                            Optional<ChartService> c = service(ChartService.class);
                            if (id.isEmpty()
                                    || c.isEmpty()
                                    || !(Args.single(e, x, 1) instanceof Player p)) {
                                return List.of();
                            }
                            return Args.one(c.get().chartItem(p, id.get()).orElse(null));
                        })
                .register(r, "[the] harbor chart (item|map) (of|for) region %string% for %player%");

        r.text(
                Spec.single("harbor fishing spot near"),
                (e, x) -> {
                    Location l = loc(e, x, 0);
                    double radius = Args.number(e, x, 1).map(Number::doubleValue).orElse(0.0);
                    Optional<FishingSpotService> s = service(FishingSpotService.class);
                    if (l == null || s.isEmpty() || radius <= 0) {
                        return List.of();
                    }
                    return Args.one(s.get().spotNear(l, radius).map(FishingSpot::id).orElse(null));
                },
                "[the] harbor fishing spot near %location% within %number% [blocks]");
        DomainExpr.of(Location.class, "harbor fishing spot locations")
                .single()
                .get(
                        (e, x) -> {
                            Optional<RegionId> id = regionArg(e, x, 0);
                            Optional<FishingSpotService> s = service(FishingSpotService.class);
                            if (id.isEmpty() || s.isEmpty()) {
                                return List.of();
                            }
                            List<Location> out = new ArrayList<>();
                            for (FishingSpot spot : s.get().spots(id.get())) {
                                if (!spot.hidden()) {
                                    Location l = spotLocation(spot);
                                    if (l != null) {
                                        out.add(l);
                                    }
                                }
                            }
                            return out;
                        })
                .register(r, "[all] [the] harbor fishing spot locations (in|of) region %string%");
    }

    private static List<Number> chart(
            Event e, Expression<?>[] x, Function<ChartService.ChartProgress, Number> f) {
        Optional<RegionId> id = regionArg(e, x, 1);
        Optional<ChartService> c = service(ChartService.class);
        if (id.isEmpty() || c.isEmpty()) {
            return List.of();
        }
        return Args.perPlayer(
                e, x, p -> c.get().progress(p.getUniqueId(), id.get()).map(f).orElse(null));
    }

    private static @Nullable Location spotLocation(FishingSpot spot) {
        World w = Bukkit.getWorld(spot.world());
        return w == null ? null : new Location(w, spot.x(), spot.y(), spot.z());
    }

    // ------------------------------------------------------------------ sea events

    private static void seaEvents(Registrar r) {
        r.text(
                Spec.single("harbor active sea events"),
                (e, x) -> {
                    Optional<SeaEventService> s = service(SeaEventService.class);
                    if (s.isEmpty()) {
                        return List.of();
                    }
                    Optional<RegionId> id = regionArg(e, x, 0);
                    List<ActiveSeaEvent> list =
                            id.isPresent() ? s.get().active(id.get()) : s.get().active();
                    return list.stream().map(a -> a.eventId().toString()).toList();
                },
                "[all] [the] active harbor sea events [in region %-string%]");
        r.text(
                Spec.single("harbor upcoming sea events"),
                (e, x) ->
                        service(SeaEventService.class)
                                .map(
                                        s ->
                                                s.upcoming().stream()
                                                        .map(a -> a.eventId().toString())
                                                        .toList())
                                .orElse(List.of()),
                "[all] [the] upcoming harbor sea events");
        r.condition(
                Spec.single("harbor storm at"),
                (e, x) -> {
                    Location l = loc(e, x, 0);
                    Optional<SeaEventService> s = service(SeaEventService.class);
                    return l != null && s.isPresent() && s.get().stormAt(l).isPresent();
                },
                "%location% is in [a] harbor storm",
                "%location% (isn't|is not) in [a] harbor storm");

        r.text(
                Spec.single("harbor sea event name"),
                (e, x) -> {
                    ActiveSeaEvent s = seaEvent(e);
                    return s == null ? List.of() : List.of(s.name());
                },
                "[the] harbor sea event name");
        r.text(
                Spec.single("harbor sea event region"),
                (e, x) -> {
                    ActiveSeaEvent s = seaEvent(e);
                    return s == null || s.region() == null
                            ? List.of()
                            : List.of(String.valueOf(s.region()));
                },
                "[the] harbor sea event region");
        r.text(
                Spec.single("harbor sea event type"),
                (e, x) -> {
                    ActiveSeaEvent s = seaEvent(e);
                    return s == null ? List.of() : List.of(s.type().key());
                },
                "[the] harbor sea event type");
        r.number(
                Spec.single("harbor sea event seconds left"),
                (e, x) -> {
                    ActiveSeaEvent s = seaEvent(e);
                    if (s == null) {
                        return List.of();
                    }
                    Instant until =
                            s.phase() == ActiveSeaEvent.Phase.WARN ? s.startsAt() : s.endsAt();
                    return List.of(
                            Math.max(0, until.getEpochSecond() - Instant.now().getEpochSecond()));
                },
                "[the] harbor sea event (seconds left|remaining seconds)");
    }

    private static @Nullable ActiveSeaEvent seaEvent(Event e) {
        return switch (e) {
            case SeaEventStartEvent s -> s.seaEvent();
            case SeaEventEndEvent s -> s.seaEvent();
            case SeaEventWarnEvent s -> s.seaEvent();
            default -> null;
        };
    }

    // ------------------------------------------------------------------ api events

    private static void events(Registrar r) {
        r.event(
                "Harbor Sea Event Start",
                SeaEventStartEvent.class,
                "해역 이벤트가 시작된 뒤 (event-string = 해역 이벤트 ID)",
                "harbor sea event start");
        r.value(SeaEventStartEvent.class, String.class, e -> e.seaEvent().eventId().toString());
        r.event(
                "Harbor Sea Event End",
                SeaEventEndEvent.class,
                "해역 이벤트가 끝난 뒤 (event-string = 해역 이벤트 ID)",
                "harbor sea event end");
        r.value(SeaEventEndEvent.class, String.class, e -> e.seaEvent().eventId().toString());
        r.event(
                "Harbor Sea Event Warn",
                SeaEventWarnEvent.class,
                "해역 이벤트가 예고된 뒤 (event-string = 해역 이벤트 ID)",
                "harbor sea event warn[ing]");
        r.value(SeaEventWarnEvent.class, String.class, e -> e.seaEvent().eventId().toString());

        r.event(
                "Harbor Region Enter",
                RegionEnterEvent.class,
                "지역에 들어간 뒤 (event-player, event-string = 지역 ID, harbor previous region)",
                "harbor region enter");
        r.value(RegionEnterEvent.class, String.class, e -> e.region().toString());
        r.event(
                "Harbor Region Leave",
                RegionLeaveEvent.class,
                "지역에서 나간 뒤 (event-player, event-string = 지역 ID, harbor next region)",
                "harbor region leave");
        r.value(RegionLeaveEvent.class, String.class, e -> e.region().toString());
        r.event(
                "Harbor Region First Visit",
                RegionFirstVisitEvent.class,
                "이번 시즌 처음 지역에 들어간 뒤 (event-player, event-string = 지역 ID)",
                "harbor region first visit");
        r.value(RegionFirstVisitEvent.class, String.class, e -> e.region().toString());
        r.event(
                "Harbor Region Block",
                RegionBlockedEvent.class,
                "입장 조건이 모자라 밀려난 뒤 (event-player, event-string = 지역 ID, harbor blocked"
                        + " requirements)",
                "harbor region block[ed]");
        r.value(RegionBlockedEvent.class, String.class, e -> e.region().toString());
        r.text(
                Spec.in("harbor previous region", RegionEnterEvent.class),
                (e, x) -> {
                    if (!(e instanceof RegionEnterEvent en)) {
                        return List.of();
                    }
                    RegionInfo from = en.from();
                    return from == null ? List.of() : List.of(from.id().toString());
                },
                "[the] harbor previous region");
        r.text(
                Spec.in("harbor next region", RegionLeaveEvent.class),
                (e, x) -> {
                    if (!(e instanceof RegionLeaveEvent l)) {
                        return List.of();
                    }
                    RegionInfo to = l.to();
                    return to == null ? List.of() : List.of(to.id().toString());
                },
                "[the] harbor next region");
        r.text(
                Spec.in("harbor blocked requirements", RegionBlockedEvent.class),
                (e, x) ->
                        e instanceof RegionBlockedEvent b
                                ? b.status().missing().stream()
                                        .map(m -> key(m.kind()) + ":" + m.detail())
                                        .toList()
                                : List.of(),
                "[the] harbor blocked requirements");

        r.event(
                "Harbor Chart Progress",
                ChartProgressEvent.class,
                "해도 완성도가 1%p 이상 오르거나 보상 단계에 닿은 뒤 (event-player, event-string = 지역 ID,"
                        + " event-number = 완성도 %, harbor chart reward step)",
                "harbor chart progress");
        r.value(ChartProgressEvent.class, String.class, e -> e.region().toString());
        r.value(ChartProgressEvent.class, Number.class, ChartProgressEvent::percent);
        r.number(
                Spec.in("harbor chart reward step", ChartProgressEvent.class),
                (e, x) -> e instanceof ChartProgressEvent c ? List.of(c.step()) : List.of(),
                "[the] harbor chart reward step");

        r.event(
                "Harbor Fishing Spot Move",
                FishingSpotMovedEvent.class,
                "물고기 떼가 새 자리로 옮긴 뒤 (event-string = 자리 ID, event-location = 새 위치)",
                "harbor fishing spot move[d]");
        r.value(FishingSpotMovedEvent.class, String.class, e -> e.spot().id());
        r.value(FishingSpotMovedEvent.class, Location.class, e -> spotLocation(e.spot()));

        r.event(
                "Harbor Plot Like",
                PlotLikedEvent.class,
                "방문자가 하우징 부지에 좋아요를 누른 뒤 (event-player = 누른 사람, event-offlineplayer = 주인,"
                        + " event-number = 좋아요 수)",
                "harbor plot like[d]");
        r.value(PlotLikedEvent.class, OfflinePlayer.class, e -> offline(e.plot().owner()));
        r.value(PlotLikedEvent.class, Number.class, PlotLikedEvent::likes);
        r.event(
                "Harbor Plot Load",
                PlotLoadedEvent.class,
                "하우징 부지를 불러온 뒤 (event-offlineplayer = 주인, event-number = 부지 ID)",
                "harbor plot load[ed]");
        r.value(PlotLoadedEvent.class, OfflinePlayer.class, e -> offline(e.plot().owner()));
        r.value(PlotLoadedEvent.class, Number.class, e -> e.plot().plotId());
        r.event(
                "Harbor Plot Unload",
                PlotUnloadedEvent.class,
                "하우징 부지를 내린 뒤 (event-offlineplayer = 주인, event-number = 부지 ID)",
                "harbor plot unload[ed]");
        r.value(PlotUnloadedEvent.class, OfflinePlayer.class, e -> offline(e.plot().owner()));
        r.value(PlotUnloadedEvent.class, Number.class, e -> e.plot().plotId());
        r.event(
                "Harbor Display Anchor Change",
                DisplayAnchorChangedEvent.class,
                "개인 수족관 수조를 놓거나 치운 뒤 (event-offlineplayer = 부지 주인, event-number = 슬롯,"
                        + " event-string = 장식 ID, harbor display anchor was added)",
                "harbor display anchor change[d]",
                "harbor aquarium tank change[d]");
        r.value(
                DisplayAnchorChangedEvent.class,
                OfflinePlayer.class,
                e -> offline(e.plot().owner()));
        r.value(DisplayAnchorChangedEvent.class, Number.class, e -> e.anchor().slot());
        r.value(DisplayAnchorChangedEvent.class, String.class, e -> e.anchor().decor().toString());
        r.condition(
                Spec.in("harbor display anchor added", DisplayAnchorChangedEvent.class),
                (e, x) -> e instanceof DisplayAnchorChangedEvent d && d.added(),
                "[the] harbor display anchor was added",
                "[the] harbor display anchor was removed");

        r.event(
                "Harbor Ship Summon",
                ShipSummonEvent.class,
                "배를 띄우기 직전 (event-player, event-string = 배 종류 ID, event-number = 배 번호). cancel"
                        + " event로 막는다",
                "harbor ship summon");
        r.value(ShipSummonEvent.class, String.class, e -> e.ship().type().toString());
        r.value(ShipSummonEvent.class, Number.class, e -> e.ship().shipId());
        r.event(
                "Harbor Ship Wreck",
                ShipWreckedEvent.class,
                "배 내구도가 0이 되어 난파한 뒤 (event-player = 주인, event-string = 배 종류 ID, event-number ="
                        + " 배 번호)",
                "harbor ship wreck[ed]");
        r.value(ShipWreckedEvent.class, String.class, e -> e.ship().type().toString());
        r.value(ShipWreckedEvent.class, Number.class, e -> e.ship().shipId());
        r.text(
                Spec.single("harbor event ship name"),
                (e, x) -> {
                    ShipInfo s = eventShip(e);
                    return s == null ? List.of() : List.of(s.name());
                },
                "[the] harbor event ship name");

        r.event(
                "Harbor Treasure Claim",
                TreasureClaimedEvent.class,
                "보물 지도를 찾아 보상을 받은 뒤 (event-player, event-string = 지역 ID, event-number = 골드,"
                        + " harbor treasure tier · site)",
                "harbor treasure claim[ed]");
        r.value(TreasureClaimedEvent.class, String.class, e -> e.region().toString());
        r.value(TreasureClaimedEvent.class, Number.class, TreasureClaimedEvent::gold);
        r.text(
                Spec.in("harbor treasure tier", TreasureClaimedEvent.class),
                (e, x) -> e instanceof TreasureClaimedEvent t ? List.of(t.tier()) : List.of(),
                "[the] harbor treasure tier");
        r.text(
                Spec.in("harbor treasure site", TreasureClaimedEvent.class),
                (e, x) -> e instanceof TreasureClaimedEvent t ? List.of(t.siteId()) : List.of(),
                "[the] harbor treasure site");
        r.condition(
                Spec.in("harbor treasure hint", TreasureClaimedEvent.class),
                (e, x) -> e instanceof TreasureClaimedEvent t && t.hint(),
                "[the] harbor treasure (has|gave) [a] hint",
                "[the] harbor treasure (doesn't|does not|didn't|did not) (have|give) [a] hint");
    }

    private static @Nullable ShipInfo eventShip(Event e) {
        return switch (e) {
            case ShipSummonEvent s -> s.ship();
            case ShipWreckedEvent s -> s.ship();
            default -> null;
        };
    }
}
