package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.CollectionEntry;
import wiki.creeper.harbor.collection.api.CollectionScope;
import wiki.creeper.harbor.collection.api.CollectionService;
import wiki.creeper.harbor.collection.api.DisplayCaseService;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.api.ReleaseService;
import wiki.creeper.harbor.collection.api.WeeklyRecord;
import wiki.creeper.harbor.collection.api.event.AquariumDonatedEvent;
import wiki.creeper.harbor.collection.api.event.AquariumStageChangedEvent;
import wiki.creeper.harbor.collection.api.event.CollectionMilestoneEvent;
import wiki.creeper.harbor.collection.api.event.CollectionRegisteredEvent;
import wiki.creeper.harbor.collection.api.event.FishReleasedEvent;
import wiki.creeper.harbor.collection.api.event.RecordBrokenEvent;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Collection is enabled: collection completion and entries (count · best size · best
 * quality · first catch, season and total), hints, server records, the aquarium (status, donation),
 * release, personal display cases, opening the collection, and every Collection api event.
 *
 * <p>Display case values come from an async API and are eventually consistent ({@link AsyncCache}).
 * Collection entries are written by catches; {@code harbor admin set collection entry} and {@code
 * harbor admin remove collection entry} do what {@code /harbor collection entry} does.
 */
public final class CollectionSyntax {

    private CollectionSyntax() {}

    private static final String SOURCE = "skript";

    private static final AsyncCache<UUID, Integer> DISPLAY_SLOTS =
            AsyncCache.every(
                    30_000,
                    owner -> service(DisplayCaseService.class).orElseThrow().slotCount(owner));
    private static final AsyncCache<UUID, Integer> THEME_SLOTS =
            AsyncCache.every(
                    30_000,
                    owner -> service(DisplayCaseService.class).orElseThrow().themeSlotCount(owner));
    private static final AsyncCache<Long, List<DisplaySlot>> PLOT_SLOTS =
            AsyncCache.every(
                    5_000, plot -> service(DisplayCaseService.class).orElseThrow().slots(plot));

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<CollectionService> collection() {
        return service(CollectionService.class);
    }

    private static Optional<FishId> fish(String raw) {
        return Ids.parse(raw, FishId.PREFIX).map(FishId::new);
    }

    private static Optional<RegionId> region(String raw) {
        return Ids.parse(raw, RegionId.PREFIX).map(RegionId::new);
    }

    private static <E extends Event, V> Spec.Getter<V> in(
            Class<E> type, Function<E, @Nullable V> value) {
        return (e, x) -> type.isInstance(e) ? Args.one(value.apply(type.cast(e))) : List.of();
    }

    /** Value from a player's collection entry for the fish in argument 1. */
    private static <T> Spec.Getter<T> entry(
            CollectionScope scope, Function<CollectionEntry, @Nullable T> value) {
        return (e, x) -> {
            Optional<FishId> f = Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
            Optional<CollectionService> c = collection();
            if (f.isEmpty() || c.isEmpty()) {
                return List.of();
            }
            return Args.perPlayer(
                    e,
                    x,
                    p -> c.get().entry(p.getUniqueId(), f.get(), scope).map(value).orElse(null));
        };
    }

    private static List<RecordEntry> top(Event e, ch.njol.skript.lang.Expression<?>[] x) {
        Optional<FishId> f = Args.string(e, x, 0).flatMap(CollectionSyntax::fish);
        Optional<RecordService> records = service(RecordService.class);
        if (f.isEmpty() || records.isEmpty()) {
            return List.of();
        }
        String league = Args.string(e, x, 1).orElse("all");
        int limit = Math.clamp(Args.number(e, x, 2).map(Number::intValue).orElse(10), 1, 10);
        return records.get().serverTop(f.get(), league, limit);
    }

    public static void register(Registrar r) {
        registerEntries(r);
        registerAdmin(r);
        registerHints(r);
        registerRecords(r);
        registerAquarium(r);
        registerDisplay(r);
        registerEvents(r);
    }

    // ------------------------------------------------------------------ collection entries

    private static void registerEntries(Registrar r) {
        r.number(
                Spec.of("harbor collection percent"),
                (e, x) ->
                        collection()
                                .map(
                                        c ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                c.percent(
                                                                        p.getUniqueId(),
                                                                        CollectionScope.SEASON,
                                                                        null)))
                                .orElse(List.of()),
                "[the] harbor [season] collection percent[age] of %players%");
        r.number(
                Spec.of("harbor total collection percent"),
                (e, x) ->
                        collection()
                                .map(
                                        c ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                c.percent(
                                                                        p.getUniqueId(),
                                                                        CollectionScope.TOTAL,
                                                                        null)))
                                .orElse(List.of()),
                "[the] harbor total collection percent[age] of %players%");
        r.number(
                Spec.of("harbor region collection percent"),
                (e, x) -> {
                    Optional<RegionId> reg = Args.string(e, x, 1).flatMap(CollectionSyntax::region);
                    Optional<CollectionService> c = collection();
                    if (reg.isEmpty() || c.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p ->
                                    c.get()
                                            .percent(
                                                    p.getUniqueId(),
                                                    CollectionScope.SEASON,
                                                    reg.get()));
                },
                "[the] harbor collection percent[age] of %players% in [region] %string%");
        r.condition(
                Spec.of("harbor collection registered"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                    Optional<CollectionService> c = collection();
                    return fish.isPresent()
                            && c.isPresent()
                            && Args.all(
                                    e,
                                    x,
                                    p ->
                                            c.get()
                                                    .isRegistered(
                                                            p.getUniqueId(),
                                                            fish.get(),
                                                            CollectionScope.TOTAL));
                },
                "%players% (has|have) registered harbor fish %string%",
                "%players% (hasn't|has not|haven't|have not) registered harbor fish %string%");
        r.condition(
                Spec.of("harbor season collection registered"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                    Optional<CollectionService> c = collection();
                    return fish.isPresent()
                            && c.isPresent()
                            && Args.all(
                                    e,
                                    x,
                                    p ->
                                            c.get()
                                                    .isRegistered(
                                                            p.getUniqueId(),
                                                            fish.get(),
                                                            CollectionScope.SEASON));
                },
                "%players% (has|have) registered harbor fish %string% this season",
                "%players% (hasn't|has not|haven't|have not) registered harbor fish %string% this"
                        + " season");
        for (CollectionScope scope : CollectionScope.values()) {
            String word = scope == CollectionScope.TOTAL ? "[total]" : "season";
            String label = scope.name().toLowerCase(Locale.ROOT);
            r.number(
                    Spec.of("harbor " + label + " collection count"),
                    entry(scope, CollectionEntry::count),
                    "[the] harbor "
                            + word
                            + " collection (count|catches) of %players% for [fish] %string%");
            r.number(
                    Spec.of("harbor " + label + " collection size"),
                    entry(scope, CollectionEntry::maxSize),
                    "[the] harbor "
                            + word
                            + " collection (size|max size) of %players% for [fish] %string%");
            r.number(
                    Spec.of("harbor " + label + " collection quality"),
                    entry(scope, CollectionEntry::bestQuality),
                    "[the] harbor "
                            + word
                            + " collection quality of %players% for [fish] %string%");
            Ext.expr(
                            r,
                            Date.class,
                            Spec.of("harbor " + label + " collection first catch"),
                            entry(scope, c -> new Date(c.firstCaught().toEpochMilli())))
                    .register(
                            "[the] harbor "
                                    + word
                                    + " collection first catch [date] of %players% for [fish]"
                                    + " %string%");
        }
        r.number(
                Spec.of("harbor best size"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                    Optional<RecordService> records = service(RecordService.class);
                    if (fish.isEmpty() || records.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p ->
                                    records.get()
                                            .personalBest(p.getUniqueId(), fish.get())
                                            .map(d -> (Number) d)
                                            .orElse(null));
                },
                "[the] harbor (best|record) size of %players% for [fish] %string%");
        r.effect(
                Spec.of("open harbor collection"),
                (e, x) -> {
                    Optional<CollectionService> c = collection();
                    if (c.isEmpty()) {
                        return;
                    }
                    Object owner = Args.single(e, x, 0);
                    int page = Math.max(0, Args.number(e, x, 2).map(Number::intValue).orElse(0));
                    for (Player viewer : Args.players(e, x, 1)) {
                        c.get()
                                .open(
                                        viewer,
                                        owner instanceof OfflinePlayer o
                                                ? o.getUniqueId()
                                                : viewer.getUniqueId(),
                                        page);
                    }
                },
                "open harbor collection [of %-offlineplayer%] (to|for) %players% [at page"
                        + " %-number%]");
    }

    // ------------------------------------------------------------------ hints

    private static void registerAdmin(Registrar r) {
        // Same as /harbor collection entry set · remove (online players on this server)
        r.effect(
                Spec.of("harbor admin set collection entry"),
                (e, x) -> {
                    Optional<CollectionService> s = collection();
                    Optional<FishId> f = Args.string(e, x, 0).flatMap(CollectionSyntax::fish);
                    if (s.isEmpty() || f.isEmpty()) {
                        return;
                    }
                    Double size = Args.number(e, x, 2).map(Number::doubleValue).orElse(null);
                    Integer quality = Args.number(e, x, 3).map(Number::intValue).orElse(null);
                    for (Player p : Args.players(e, x, 1)) {
                        s.get().registerEntry(p.getUniqueId(), f.get(), size, quality);
                    }
                },
                "harbor admin (set|register) [harbor] collection entry [of] %string% for %players%"
                        + " [with size %-number%] [with quality %-number%]");
        r.effect(
                Spec.of("harbor admin remove collection entry"),
                (e, x) -> {
                    Optional<CollectionService> s = collection();
                    Optional<FishId> f = Args.string(e, x, 0).flatMap(CollectionSyntax::fish);
                    if (s.isEmpty() || f.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        s.get().removeEntry(p.getUniqueId(), f.get());
                    }
                },
                "harbor admin (remove|delete) [harbor] collection entry [of] %string% (from|for)"
                        + " %players%");
    }

    private static void registerHints(Registrar r) {
        Ext.expr(
                        r,
                        Number.class,
                        Spec.of("harbor hint level"),
                        (e, x) -> {
                            Optional<FishId> f =
                                    Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                            Optional<HintService> h = service(HintService.class);
                            if (f.isEmpty() || h.isEmpty()) {
                                return List.of();
                            }
                            return Args.perPlayer(
                                    e, x, p -> h.get().level(p.getUniqueId(), f.get()));
                        })
                .valueChanger(
                        Number.class,
                        (e, x, mode, v) -> {
                            Optional<FishId> f =
                                    Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                            Optional<HintService> h = service(HintService.class);
                            if (f.isEmpty() || h.isEmpty() || !(v instanceof Number n)) {
                                return;
                            }
                            for (Player p : Args.players(e, x, 0)) {
                                int level =
                                        mode == ChangeMode.ADD
                                                ? h.get().level(p.getUniqueId(), f.get())
                                                        + n.intValue()
                                                : n.intValue();
                                var _ =
                                        h.get()
                                                .grant(
                                                        p.getUniqueId(),
                                                        f.get(),
                                                        Math.clamp(level, 0, HintService.MAX_LEVEL),
                                                        SOURCE);
                            }
                        },
                        ChangeMode.SET,
                        ChangeMode.ADD)
                .register("[the] harbor hint level of %players% for [fish] %string%");
        r.effect(
                Spec.of("harbor grant random hint"),
                (e, x) -> {
                    Optional<HintService> h = service(HintService.class);
                    if (h.isEmpty()) {
                        return;
                    }
                    RegionId reg =
                            Args.string(e, x, 1).flatMap(CollectionSyntax::region).orElse(null);
                    for (Player p : Args.players(e, x, 0)) {
                        var _ = h.get().grantRandom(p.getUniqueId(), reg, SOURCE);
                    }
                },
                "harbor grant [a] random hint to %players% [in region %-string%]");
    }

    // ------------------------------------------------------------------ records

    private static void registerRecords(Registrar r) {
        String args = "for [fish] %string% [in league %-string%] [limit %-number%]";
        Ext.expr(
                        r,
                        String.class,
                        Spec.single("harbor server top names"),
                        (e, x) -> top(e, x).stream().map(RecordEntry::name).toList())
                .list()
                .register("[the] harbor server top names " + args);
        Ext.expr(
                        r,
                        Number.class,
                        Spec.single("harbor server top sizes"),
                        (e, x) -> top(e, x).stream().map(t -> (Number) t.size()).toList())
                .list()
                .register("[the] harbor server top sizes " + args);
        Ext.expr(
                        r,
                        OfflinePlayer.class,
                        Spec.single("harbor server top players"),
                        (e, x) ->
                                top(e, x).stream()
                                        .map(t -> Bukkit.getOfflinePlayer(t.player()))
                                        .toList())
                .list()
                .register("[the] harbor server top players " + args);

        // This week's server records (newest first, as received by this server)
        Ext.expr(
                        r,
                        String.class,
                        Spec.single("harbor weekly record fish"),
                        (e, x) -> weekly().stream().map(w -> w.fish().toString()).toList())
                .list()
                .register("[all] [the] harbor weekly record (fish|species)");
        Ext.expr(
                        r,
                        String.class,
                        Spec.single("harbor weekly record names"),
                        (e, x) -> weekly().stream().map(w -> w.entry().name()).toList())
                .list()
                .register("[all] [the] harbor weekly record names");
        Ext.expr(
                        r,
                        OfflinePlayer.class,
                        Spec.single("harbor weekly record players"),
                        (e, x) ->
                                weekly().stream()
                                        .map(w -> Bukkit.getOfflinePlayer(w.entry().player()))
                                        .toList())
                .list()
                .register("[all] [the] harbor weekly record players");
        Ext.expr(
                        r,
                        Number.class,
                        Spec.single("harbor weekly record sizes"),
                        (e, x) -> weekly().stream().map(w -> (Number) w.entry().size()).toList())
                .list()
                .register("[all] [the] harbor weekly record sizes");
        Ext.expr(
                        r,
                        Date.class,
                        Spec.single("harbor weekly record dates"),
                        (e, x) ->
                                weekly().stream()
                                        .map(w -> new Date(w.entry().caughtAt().toEpochMilli()))
                                        .toList())
                .list()
                .register("[all] [the] harbor weekly record (dates|times)");
    }

    private static List<WeeklyRecord> weekly() {
        return service(RecordService.class).map(RecordService::weeklyRecords).orElse(List.of());
    }

    // ------------------------------------------------------------------ aquarium · release

    private static void registerAquarium(Registrar r) {
        r.number(
                Spec.single("harbor aquarium points"),
                (e, x) ->
                        service(AquariumService.class)
                                .map(a -> List.<Number>of(a.status().points()))
                                .orElse(List.of()),
                "[the] harbor aquarium points");
        r.number(
                Spec.single("harbor aquarium species"),
                (e, x) ->
                        service(AquariumService.class)
                                .map(a -> List.<Number>of(a.status().species()))
                                .orElse(List.of()),
                "[the] harbor aquarium [donated] species [count]");
        r.number(
                Spec.single("harbor aquarium donatable"),
                (e, x) ->
                        service(AquariumService.class)
                                .map(a -> List.<Number>of(a.status().donatable()))
                                .orElse(List.of()),
                "[the] harbor aquarium donatable [species] [count]");
        r.number(
                Spec.single("harbor aquarium rate"),
                (e, x) ->
                        service(AquariumService.class)
                                .map(a -> List.<Number>of(a.status().rate()))
                                .orElse(List.of()),
                "[the] harbor aquarium (rate|progress)");
        r.number(
                Spec.single("harbor aquarium stage"),
                (e, x) ->
                        service(AquariumService.class)
                                .map(a -> List.<Number>of(a.status().stage()))
                                .orElse(List.of()),
                "[the] harbor aquarium stage");
        r.effect(
                Spec.of("open harbor aquarium donation box"),
                (e, x) ->
                        service(AquariumService.class)
                                .ifPresent(a -> Args.players(e, x, 0).forEach(a::openDonationBox)),
                "open harbor aquarium donation box (to|for) %players%");
        r.effect(
                Spec.of("harbor donate held fish"),
                (e, x) ->
                        service(AquariumService.class)
                                .ifPresent(
                                        a ->
                                                Args.players(e, x, 0)
                                                        .forEach(
                                                                p -> {
                                                                    var _ = a.donateHeld(p);
                                                                })),
                "make %players% donate [their] held fish to [the] harbor aquarium");
        r.condition(
                Spec.single("harbor release available"),
                (e, x) ->
                        service(ReleaseService.class).map(ReleaseService::available).orElse(false),
                "harbor release is (available|open)",
                "harbor release (isn't|is not) (available|open)");
        r.effect(
                Spec.of("harbor release held fish"),
                (e, x) -> {
                    Optional<ReleaseService> rel = service(ReleaseService.class);
                    if (rel.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 0)) {
                        ItemStack hand = p.getInventory().getItemInMainHand();
                        if (!hand.isEmpty()) {
                            var _ = rel.get().release(p, hand);
                        }
                    }
                },
                "make %players% release [their] held harbor fish");
    }

    // ------------------------------------------------------------------ display cases

    private static void registerDisplay(Registrar r) {
        r.number(
                Spec.of("harbor display slot count"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        DISPLAY_SLOTS
                                                .latest(p.getUniqueId())
                                                .map(n -> (Number) n)
                                                .orElse(null)),
                "[the] harbor display slot count of %players%");
        r.number(
                Spec.of("harbor display theme slot count"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        THEME_SLOTS
                                                .latest(p.getUniqueId())
                                                .map(n -> (Number) n)
                                                .orElse(null)),
                "[the] harbor display theme slot count of %players%");
        Ext.expr(
                        r,
                        String.class,
                        Spec.single("harbor displayed fish"),
                        (e, x) ->
                                Args.number(e, x, 0)
                                        .flatMap(n -> PLOT_SLOTS.latest(n.longValue()))
                                        .map(l -> l.stream().map(s -> s.fish().toString()).toList())
                                        .orElse(List.of()))
                .list()
                .register("[all] [the] harbor displayed fish of plot %number%");
        r.text(
                Spec.single("harbor displayed fish in slot"),
                (e, x) -> {
                    Optional<Number> plot = Args.number(e, x, 1);
                    Optional<Number> slot = Args.number(e, x, 0);
                    if (plot.isEmpty() || slot.isEmpty()) {
                        return List.of();
                    }
                    return PLOT_SLOTS
                            .latest(plot.get().longValue())
                            .flatMap(
                                    l ->
                                            l.stream()
                                                    .filter(s -> s.slot() == slot.get().intValue())
                                                    .findFirst())
                            .map(s -> List.of(s.fish().toString()))
                            .orElse(List.of());
                },
                "[the] harbor displayed fish in slot %number% of plot %number%");
        r.effect(
                Spec.of("harbor display fish"),
                (e, x) -> {
                    Optional<DisplayCaseService> d = service(DisplayCaseService.class);
                    Optional<FishId> f = Args.string(e, x, 0).flatMap(CollectionSyntax::fish);
                    Optional<Number> slot = Args.number(e, x, 1);
                    Optional<Number> plot = Args.number(e, x, 2);
                    if (d.isEmpty() || f.isEmpty() || slot.isEmpty() || plot.isEmpty()) {
                        return;
                    }
                    long plotId = plot.get().longValue();
                    for (Player p : Args.players(e, x, 3)) {
                        var _ =
                                d.get()
                                        .set(p, plotId, slot.get().intValue(), f.get())
                                        .whenComplete((ok, err) -> PLOT_SLOTS.refresh(plotId));
                    }
                },
                "harbor display fish %string% in slot %number% of plot %number% as %players%");
        r.effect(
                Spec.single("harbor clear display slot"),
                (e, x) -> {
                    Optional<DisplayCaseService> d = service(DisplayCaseService.class);
                    Optional<Number> slot = Args.number(e, x, 0);
                    Optional<Number> plot = Args.number(e, x, 1);
                    if (d.isEmpty() || slot.isEmpty() || plot.isEmpty()) {
                        return;
                    }
                    long plotId = plot.get().longValue();
                    var _ =
                            d.get()
                                    .clear(null, plotId, slot.get().intValue())
                                    .whenComplete((ok, err) -> PLOT_SLOTS.refresh(plotId));
                },
                "harbor admin clear display slot %number% of plot %number%");
    }

    // ------------------------------------------------------------------ events

    private static void registerEvents(Registrar r) {
        r.event(
                "Harbor Collection Register",
                CollectionRegisteredEvent.class,
                "도감에 새 어종이 등록된 뒤 (event-offlineplayer, event-string = 어종 ID). 시즌·누적 각각 한 번씩",
                "harbor collection regist(er|ration)");
        r.value(
                CollectionRegisteredEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(CollectionRegisteredEvent.class, String.class, e -> e.fish().id().toString());
        r.text(
                Spec.in("harbor collection scope", CollectionRegisteredEvent.class),
                in(CollectionRegisteredEvent.class, c -> c.scope().name().toLowerCase(Locale.ROOT)),
                "[the] harbor collection scope");
        r.text(
                Spec.in("harbor registered grade", CollectionRegisteredEvent.class),
                in(CollectionRegisteredEvent.class, c -> c.grade().key()),
                "[the] harbor registered grade");

        r.event(
                "Harbor Collection Milestone",
                CollectionMilestoneEvent.class,
                "도감 달성 단계 (event-offlineplayer, event-string = 지역 이름·단계 키, event-number = 완성률)",
                "harbor collection milestone");
        r.value(
                CollectionMilestoneEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(CollectionMilestoneEvent.class, String.class, CollectionMilestoneEvent::key);
        r.value(CollectionMilestoneEvent.class, Number.class, CollectionMilestoneEvent::percent);
        r.text(
                Spec.in("harbor milestone kind", CollectionMilestoneEvent.class),
                in(CollectionMilestoneEvent.class, m -> m.kind().name().toLowerCase(Locale.ROOT)),
                "[the] harbor milestone kind");
        r.text(
                Spec.in("harbor milestone title", CollectionMilestoneEvent.class),
                in(CollectionMilestoneEvent.class, CollectionMilestoneEvent::title),
                "[the] harbor milestone title");

        r.event(
                "Harbor Record Broken",
                RecordBrokenEvent.class,
                "최대어 기록을 깬 뒤 (event-offlineplayer, event-string = 어종 ID, event-number = 크기 cm)",
                "harbor record (broken|break)");
        r.value(
                RecordBrokenEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(RecordBrokenEvent.class, String.class, e -> e.fish().id().toString());
        r.value(RecordBrokenEvent.class, Number.class, RecordBrokenEvent::size);
        r.text(
                Spec.in("harbor record scope", RecordBrokenEvent.class),
                in(RecordBrokenEvent.class, b -> b.scope().name().toLowerCase(Locale.ROOT)),
                "[the] harbor record scope");
        r.text(
                Spec.in("harbor record league", RecordBrokenEvent.class),
                in(RecordBrokenEvent.class, RecordBrokenEvent::league),
                "[the] harbor record league");
        r.number(
                Spec.in("harbor record previous", RecordBrokenEvent.class),
                in(RecordBrokenEvent.class, RecordBrokenEvent::previous),
                "[the] harbor record previous size");

        r.event(
                "Harbor Aquarium Donate",
                AquariumDonatedEvent.class,
                "수족관에 물고기를 기증한 뒤, 한 마리마다 (event-offlineplayer, event-string = 어종 ID, event-number = 얻은 점수)",
                "harbor aquarium donat(e|ion)");
        r.value(
                AquariumDonatedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(AquariumDonatedEvent.class, String.class, e -> e.fish().id().toString());
        r.value(AquariumDonatedEvent.class, Number.class, AquariumDonatedEvent::points);
        r.text(
                Spec.in("harbor donated grade", AquariumDonatedEvent.class),
                in(AquariumDonatedEvent.class, d -> d.grade().key()),
                "[the] harbor donated grade");
        r.number(
                Spec.in("harbor donated quality", AquariumDonatedEvent.class),
                in(AquariumDonatedEvent.class, d -> d.quality().stars()),
                "[the] harbor donated quality");

        r.event(
                "Harbor Aquarium Stage",
                AquariumStageChangedEvent.class,
                "수족관 단계가 오른 뒤, 올린 서버에서만 (event-number = 새 단계)",
                "harbor aquarium stage (change|up)");
        r.value(AquariumStageChangedEvent.class, Number.class, AquariumStageChangedEvent::stage);
        r.number(
                Spec.in("harbor aquarium event season", AquariumStageChangedEvent.class),
                in(AquariumStageChangedEvent.class, AquariumStageChangedEvent::season),
                "[the] harbor aquarium event season");

        r.event(
                "Harbor Fish Release",
                FishReleasedEvent.class,
                "물고기를 방생한 뒤 (event-offlineplayer, event-string = 어종 ID, event-number = 크기 cm)",
                "harbor fish release");
        r.value(
                FishReleasedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(FishReleasedEvent.class, String.class, e -> e.fish().id().toString());
        r.value(FishReleasedEvent.class, Number.class, FishReleasedEvent::size);
        r.text(
                Spec.in("harbor released grade", FishReleasedEvent.class),
                in(FishReleasedEvent.class, f -> f.grade().key()),
                "[the] harbor released grade");
    }
}
