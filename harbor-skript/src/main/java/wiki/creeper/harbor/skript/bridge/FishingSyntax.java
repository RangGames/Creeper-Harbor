package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchResult;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.SessionInfo;
import wiki.creeper.harbor.fishing.api.TensionSession;
import wiki.creeper.harbor.fishing.api.TensionSpec;
import wiki.creeper.harbor.fishing.api.TrapInfo;
import wiki.creeper.harbor.fishing.api.TrapService;
import wiki.creeper.harbor.fishing.api.event.FishBiteEvent;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.fishing.api.event.FishRollEvent;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.api.event.MinigameEndEvent;
import wiki.creeper.harbor.fishing.api.event.QualityRolledEvent;
import wiki.creeper.harbor.fishing.api.event.TrapHarvestEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Fishing is enabled: fishing session state, pity, fish item info and creation, traps,
 * sea events, script-driven tension minigames, and every Fishing api event (cast · roll · bite ·
 * quality roll · minigame end · session end · catch · trap harvest) with event-values and
 * event-specific expressions. event-player is Skript's default value (PlayerEvent).
 *
 * <p>Roll results can be changed inside the roll events ({@code set harbor rolled fish to ...},
 * {@code set harbor rolled size to ...}); {@code harbor admin force next fish} sets the next roll
 * ahead of time like {@code /harbor fishing force}. Trap lists come from an async API, so trap
 * expressions return the last fetched value (refreshed in the background, see {@link AsyncCache}).
 * Trap actions are in {@link TrapSyntax}, food buffs in {@link FoodSyntax}.
 */
public final class FishingSyntax {

    private FishingSyntax() {}

    /** Tension sessions started by scripts (main thread only). */
    private static final Map<UUID, ScriptTension> TENSIONS = new HashMap<>();

    private record ScriptTension(TensionSession session, String tag) {}

    private static final AsyncCache<UUID, List<TrapInfo>> TRAPS =
            AsyncCache.every(
                    5_000,
                    owner ->
                            Harbor.api()
                                    .service(TrapService.class)
                                    .map(t -> t.traps(owner))
                                    .orElseThrow());

    /** Traps looked up by ID (other players' traps, not in an owner list yet). */
    private static final AsyncCache<Long, Optional<TrapInfo>> TRAP_BY_ID =
            AsyncCache.every(
                    5_000,
                    id ->
                            Harbor.api()
                                    .service(TrapService.class)
                                    .map(t -> t.trap(id))
                                    .orElseThrow());

    /** Refreshes the cached trap values after a trap action (TrapSyntax). */
    static void refreshTraps(UUID owner, long trapId) {
        TRAPS.refresh(owner);
        refreshTrap(trapId);
    }

    /** Refreshes one trap looked up by ID (no-op for 0). */
    static void refreshTrap(long trapId) {
        if (trapId > 0) {
            TRAP_BY_ID.refresh(trapId);
        }
    }

    private static Optional<FishingService> fishing() {
        return Harbor.api().service(FishingService.class);
    }

    private static Optional<FishId> fishId(String raw) {
        return Ids.parse(raw, FishId.PREFIX).map(FishId::new);
    }

    private static Optional<FishDef> fishDef(String raw) {
        return Ids.parse(raw, FishId.PREFIX)
                .flatMap(id -> Harbor.api().content().snapshot().get(FishDef.TYPE, id));
    }

    private static Optional<RegionId> region(String raw) {
        return Ids.parse(raw, RegionId.PREFIX).map(RegionId::new);
    }

    /** Value from an event of that type (empty in other events). */
    private static <E extends Event, V> Spec.Getter<V> in(
            Class<E> type, Function<E, @Nullable V> value) {
        return (e, x) -> type.isInstance(e) ? Args.one(value.apply(type.cast(e))) : List.of();
    }

    /** The species of any Fishing event that has one. */
    private static @Nullable FishDef eventFish(Event e) {
        return switch (e) {
            case FishCaughtEvent c -> c.fish();
            case FishRollEvent r -> r.fish();
            case FishBiteEvent b -> b.fish();
            case QualityRolledEvent q -> q.fish();
            case MinigameEndEvent m -> m.fish();
            case FishingSessionEndEvent s -> s.fish();
            case FishingCastEvent c -> c.fish();
            default -> null;
        };
    }

    private static List<ItemStack> items(Event e, Expression<?>[] x, int index) {
        List<ItemStack> out = new ArrayList<>();
        if (index >= x.length || x[index] == null) {
            return out;
        }
        for (Object o : x[index].getArray(e)) {
            if (o instanceof ItemStack s) {
                out.add(s);
            }
        }
        return out;
    }

    private static Optional<HarborItem.Fish> fishItem(ItemStack stack) {
        return Harbor.api()
                .items()
                .read(stack)
                .filter(HarborItem.Fish.class::isInstance)
                .map(HarborItem.Fish.class::cast);
    }

    /** One value per fish item of the first argument (non-fish items are skipped). */
    private static <T> Spec.Getter<T> perFish(Function<HarborItem.Fish, @Nullable T> value) {
        return (e, x) -> {
            List<T> out = new ArrayList<>();
            for (ItemStack s : items(e, x, 0)) {
                Optional<HarborItem.Fish> f = fishItem(s);
                if (f.isPresent()) {
                    T v = value.apply(f.get());
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
            return out;
        };
    }

    private static Optional<TrapInfo> trap(long id) {
        for (List<TrapInfo> list : TRAPS.cached()) {
            for (TrapInfo t : list) {
                if (t.id() == id) {
                    var _ = TRAPS.latest(t.owner());
                    return Optional.of(t);
                }
            }
        }
        // Not in a fetched owner list: look it up by ID (empty on the first read)
        return TRAP_BY_ID.latest(id).flatMap(t -> t);
    }

    private static <T> Spec.Getter<T> byTrap(Function<TrapInfo, @Nullable T> value) {
        return (e, x) ->
                Args.number(e, x, 0)
                        .flatMap(n -> trap(n.longValue()))
                        .map(t -> Args.one(value.apply(t)))
                        .orElse(List.of());
    }

    private static Optional<Grade> grade(String raw) {
        return Keyed.fromKey(Grade.class, raw.strip().toLowerCase(Locale.ROOT));
    }

    private static void give(Player player, ItemStack stack) {
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    public static void register(Registrar r) {
        registerSession(r);
        registerAdmin(r);
        registerItems(r);
        registerTraps(r);
        registerTension(r);
        registerEvents(r);
        TrapSyntax.register(r);
        FoodSyntax.register(r);
    }

    /** One value per player from the session snapshot. */
    private static <T> Spec.Getter<T> perSession(Function<SessionInfo, @Nullable T> value) {
        return (e, x) ->
                fishing()
                        .map(
                                f ->
                                        Args.perPlayer(
                                                e,
                                                x,
                                                p -> value.apply(f.sessionInfo(p.getUniqueId()))))
                        .orElse(List.of());
    }

    // ------------------------------------------------------------------ session · pity · sea

    private static void registerSession(Registrar r) {
        r.text(
                Spec.of("harbor fishing state"),
                (e, x) ->
                        fishing()
                                .map(
                                        f ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                f.session(p.getUniqueId())
                                                                        .name()
                                                                        .toLowerCase(Locale.ROOT)))
                                .orElse(List.of()),
                "[the] harbor fishing state of %players%",
                "%players%'[s] harbor fishing state");
        r.condition(
                Spec.of("harbor is fishing"),
                (e, x) -> {
                    Optional<FishingService> f = fishing();
                    return f.isPresent() && Args.all(e, x, p -> f.get().isFishing(p.getUniqueId()));
                },
                "%players% (is|are) harbor fishing",
                "%players% (isn't|is not|aren't|are not) harbor fishing");
        r.number(
                Spec.of("harbor pity"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(FishingSyntax::fishId);
                    Optional<FishingService> f = fishing();
                    if (fish.isEmpty() || f.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(e, x, p -> f.get().pity(p.getUniqueId(), fish.get()));
                },
                "[the] harbor pity of %players% for [fish] %string%");
        r.number(
                Spec.of("harbor missed big count"),
                (e, x) ->
                        fishing()
                                .map(
                                        f ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p -> f.missedBigCount(p.getUniqueId())))
                                .orElse(List.of()),
                "[the] harbor missed big (count|fish) of %players%");
        r.number(
                Spec.single("harbor expected share"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(FishingSyntax::fishId);
                    Optional<RegionId> reg = Args.string(e, x, 1).flatMap(FishingSyntax::region);
                    Optional<FishingService> f = fishing();
                    if (fish.isEmpty() || reg.isEmpty() || f.isEmpty()) {
                        return List.of();
                    }
                    return List.of(f.get().expectedShare(reg.get(), fish.get()));
                },
                "[the] harbor expected share of [fish] %string% in [region] %string%");
        Ext.expr(
                        r,
                        String.class,
                        Spec.single("active harbor sea events"),
                        (e, x) ->
                                fishing()
                                        .<List<String>>map(f -> List.copyOf(f.activeSeaEvents()))
                                        .orElse(List.of()))
                .list()
                .register("[all] [the] active harbor sea events");
        r.condition(
                Spec.single("harbor sea event active"),
                (e, x) -> {
                    String id = Args.string(e, x, 0).orElse("");
                    return fishing().map(f -> f.activeSeaEvents().contains(id)).orElse(false);
                },
                "harbor sea event %string% is active",
                "harbor sea event %string% (isn't|is not) active");
    }

    // ------------------------------------------------------------------ session details · admin

    private static void registerAdmin(Registrar r) {
        r.text(
                Spec.of("harbor session fish"),
                perSession(s -> s.fish() == null ? null : String.valueOf(s.fish())),
                "[the] harbor (session|current|hooked) fish of %players%");
        r.text(
                Spec.of("harbor session region"),
                perSession(s -> s.region() == null ? null : String.valueOf(s.region())),
                "[the] harbor session region of %players%");
        r.text(
                Spec.of("harbor session bait"),
                perSession(s -> s.bait() == null ? null : String.valueOf(s.bait())),
                "[the] harbor session bait of %players%");
        r.number(
                Spec.of("harbor session tension"),
                perSession(s -> s.minigame() || s.tensionSession() ? s.tension() : null),
                "[the] harbor (session|minigame) tension of %players%");
        r.number(
                Spec.of("harbor session hp"),
                perSession(s -> s.minigame() || s.tensionSession() ? s.hpRatio() : null),
                "[the] harbor (session|minigame) (hp|health) of %players%");
        r.condition(
                Spec.of("harbor bite pending"),
                (e, x) -> {
                    Optional<FishingService> f = fishing();
                    return f.isPresent()
                            && Args.all(
                                    e, x, p -> f.get().sessionInfo(p.getUniqueId()).bitePending());
                },
                "%players% (has|have) [a] harbor bite pending",
                "%players% (doesn't|does not|don't|do not) have [a] harbor bite pending");
        r.condition(
                Spec.of("harbor in minigame"),
                (e, x) -> {
                    Optional<FishingService> f = fishing();
                    return f.isPresent()
                            && Args.all(e, x, p -> f.get().sessionInfo(p.getUniqueId()).minigame());
                },
                "%players% (is|are) in [a] harbor fishing minigame",
                "%players% (isn't|is not|aren't|are not) in [a] harbor fishing minigame");
        r.text(
                Spec.of("harbor forced fish"),
                perSession(s -> s.forcedFish() == null ? null : String.valueOf(s.forcedFish())),
                "[the] harbor forced [next] fish of %players%");

        // Admin: same as /harbor fishing force · pity set
        r.effect(
                Spec.of("harbor admin force next fish"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(FishingSyntax::fishId);
                    Optional<FishingService> f = fishing();
                    if (fish.isEmpty() || f.isEmpty()) {
                        return;
                    }
                    Integer quality = Args.number(e, x, 2).map(Number::intValue).orElse(null);
                    for (Player p : Args.players(e, x, 0)) {
                        f.get().forceNextFish(p, fish.get(), quality);
                    }
                },
                "harbor admin force [the] next [harbor] fish of %players% to %string% [with"
                        + " quality %-number%]");
        r.effect(
                Spec.of("harbor admin clear forced fish"),
                (e, x) ->
                        fishing()
                                .ifPresent(
                                        f ->
                                                Args.players(e, x, 0)
                                                        .forEach(
                                                                p ->
                                                                        f.clearForcedFish(
                                                                                p.getUniqueId()))),
                "harbor admin clear [the] forced [next] [harbor] fish of %players%");
        r.effect(
                Spec.of("harbor admin set pity"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(FishingSyntax::fishId);
                    Optional<Number> value = Args.number(e, x, 2);
                    Optional<FishingService> f = fishing();
                    if (fish.isEmpty() || value.isEmpty() || f.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 0)) {
                        f.get().setPity(p.getUniqueId(), fish.get(), value.get().intValue());
                    }
                },
                "harbor admin set [the] [harbor] pity of %players% for [fish] %string% to"
                        + " %number%");
        r.effect(
                Spec.of("harbor admin reset pity"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(FishingSyntax::fishId);
                    Optional<FishingService> f = fishing();
                    if (fish.isEmpty() || f.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 0)) {
                        f.get().resetPity(p.getUniqueId(), fish.get());
                    }
                },
                "harbor admin reset [the] [harbor] pity of %players% for [fish] %string%");
        r.effect(
                Spec.of("harbor admin reset missed big"),
                (e, x) ->
                        fishing()
                                .ifPresent(
                                        f ->
                                                Args.players(e, x, 0)
                                                        .forEach(
                                                                p ->
                                                                        f.resetMissedBig(
                                                                                p.getUniqueId()))),
                "harbor admin reset [the] [harbor] missed big (count|fish) of %players%");
    }

    // ------------------------------------------------------------------ fish items

    private static void registerItems(Registrar r) {
        r.condition(
                Spec.of("harbor fish item"),
                (e, x) -> {
                    List<ItemStack> list = items(e, x, 0);
                    return !list.isEmpty() && list.stream().allMatch(s -> fishItem(s).isPresent());
                },
                "%itemstacks% (is|are) [a] harbor fish [item[s]]",
                "%itemstacks% (isn't|is not|aren't|are not) [a] harbor fish [item[s]]");
        r.condition(
                Spec.of("harbor trap catch"),
                (e, x) -> {
                    Optional<TrapService> t = Harbor.api().service(TrapService.class);
                    List<ItemStack> list = items(e, x, 0);
                    return t.isPresent()
                            && !list.isEmpty()
                            && list.stream().allMatch(s -> t.get().isTrapCatch(s));
                },
                "%itemstacks% (is|are) [a] harbor trap catch[es]",
                "%itemstacks% (isn't|is not|aren't|are not) [a] harbor trap catch[es]");
        r.text(
                Spec.of("harbor fish species"),
                perFish(f -> f.species().toString()),
                "[the] harbor fish species of %itemstacks%");
        r.text(
                Spec.of("harbor fish name"),
                perFish(
                        f ->
                                Harbor.api()
                                        .content()
                                        .snapshot()
                                        .get(FishDef.TYPE, f.species().id())
                                        .map(FishDef::name)
                                        .orElse(null)),
                "[the] harbor fish name of %itemstacks%");
        r.text(
                Spec.of("harbor fish grade"),
                perFish(
                        f ->
                                Harbor.api()
                                        .content()
                                        .snapshot()
                                        .get(FishDef.TYPE, f.species().id())
                                        .map(d -> d.grade().key())
                                        .orElse(null)),
                "[the] harbor fish grade of %itemstacks%");
        r.number(
                Spec.of("harbor fish size"),
                perFish(HarborItem.Fish::sizeCm),
                "[the] harbor fish size of %itemstacks%");
        r.number(
                Spec.of("harbor fish quality"),
                perFish(f -> f.quality().stars()),
                "[the] harbor fish quality of %itemstacks%");
        r.text(
                Spec.of("harbor fish uid"),
                perFish(f -> f.uid() == null ? null : String.valueOf(f.uid())),
                "[the] harbor fish (uid|unique id) of %itemstacks%");
        r.number(
                Spec.of("harbor fish season"),
                perFish(f -> f.season().value()),
                "[the] harbor fish season of %itemstacks%");
        r.player(
                Spec.of("harbor fish catcher"),
                perFish(f -> Bukkit.getOfflinePlayer(f.catcher().uuid())),
                "[the] harbor fish catcher of %itemstacks%");
        Ext.expr(
                        r,
                        ch.njol.skript.util.Date.class,
                        Spec.of("harbor fish caught date"),
                        perFish(f -> new ch.njol.skript.util.Date(f.caughtAt().toEpochMilli())))
                .register("[the] harbor fish caught (date|time) of %itemstacks%");

        // Creating a fish item: ★5, legendary and mythic fish get a unique ID recorded in
        // fish_ledger by Core (async), exactly like a normal catch item.
        Ext.expr(
                        r,
                        ItemStack.class,
                        Spec.single("harbor fish item"),
                        (e, x) -> {
                            Optional<FishDef> def =
                                    Args.string(e, x, 0).flatMap(FishingSyntax::fishDef);
                            if (def.isEmpty()) {
                                return List.of();
                            }
                            FishDef d = def.get();
                            double size =
                                    Args.number(e, x, 1)
                                            .map(Number::doubleValue)
                                            .orElseGet(
                                                    () ->
                                                            d.sizeMax() > d.sizeMin()
                                                                    ? ThreadLocalRandom.current()
                                                                            .nextDouble(
                                                                                    d.sizeMin(),
                                                                                    d.sizeMax())
                                                                    : d.sizeMin());
                            int stars =
                                    Math.clamp(
                                            Args.number(e, x, 2).map(Number::intValue).orElse(1),
                                            Quality.MIN,
                                            Quality.MAX);
                            PlayerRef catcher =
                                    Args.single(e, x, 3) instanceof OfflinePlayer o
                                            ? new PlayerRef(
                                                    o.getUniqueId(), String.valueOf(o.getName()))
                                            : new PlayerRef(new UUID(0, 0), "Harbor");
                            return List.of(
                                    Harbor.api()
                                            .items()
                                            .createFish(
                                                    d,
                                                    size,
                                                    Quality.of(stars),
                                                    catcher,
                                                    Instant.now()));
                        })
                .register(
                        "[a] harbor fish item %string% [with size %-number%] [with quality"
                                + " %-number%] [caught by %-offlineplayer%]");

        // Full catch through Fishing (size/quality roll, job cap, QualityRolledEvent, ledger
        // records, FishCaughtEvent with source BOSS); the item is given to the player.
        r.effect(
                Spec.of("harbor give catch"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(FishingSyntax::fishId);
                    Optional<FishingService> f = fishing();
                    if (fish.isEmpty() || f.isEmpty()) {
                        return;
                    }
                    int bonus = Math.max(0, Args.number(e, x, 2).map(Number::intValue).orElse(0));
                    CatchOptions options = new CatchOptions(bonus, 0, true);
                    for (Player p : Args.players(e, x, 1)) {
                        Optional<CatchResult> result = f.get().createCatch(p, fish.get(), options);
                        ItemStack stack = result.map(CatchResult::stack).orElse(null);
                        if (stack != null) {
                            give(p, stack);
                        }
                    }
                },
                "harbor give [a] catch of [fish] %string% to %players% [with %-number% bonus"
                        + " star[s]]");
    }

    // ------------------------------------------------------------------ traps

    private static void registerTraps(Registrar r) {
        r.number(
                Spec.of("harbor trap count"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        TRAPS.latest(p.getUniqueId())
                                                .map(l -> (Number) l.size())
                                                .orElse(null)),
                "[the] (number|amount) of harbor traps of %players%",
                "[the] harbor trap count of %players%");
        Ext.expr(
                        r,
                        Number.class,
                        Spec.of("harbor trap ids"),
                        (e, x) -> {
                            List<Number> out = new ArrayList<>();
                            for (Player p : Args.players(e, x, 0)) {
                                TRAPS.latest(p.getUniqueId())
                                        .ifPresent(l -> l.forEach(t -> out.add(t.id())));
                            }
                            return out;
                        })
                .list()
                .register("[all] [the] harbor trap ids of %players%");
        r.number(
                Spec.of("harbor trap stored total"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        TRAPS.latest(p.getUniqueId())
                                                .map(
                                                        l ->
                                                                (Number)
                                                                        l.stream()
                                                                                .mapToInt(
                                                                                        TrapInfo
                                                                                                ::stored)
                                                                                .sum())
                                                .orElse(null)),
                "[the] harbor trap stored (count|catches) of %players%");
        r.text(
                Spec.single("harbor trap region"),
                byTrap(t -> t.region().toString()),
                "[the] harbor trap region of trap %number%");
        r.text(
                Spec.single("harbor trap item"),
                byTrap(t -> t.item().toString()),
                "[the] harbor trap item of trap %number%");
        r.number(
                Spec.single("harbor trap tier"),
                byTrap(TrapInfo::tier),
                "[the] harbor trap tier of trap %number%");
        r.number(
                Spec.single("harbor trap slots"),
                byTrap(TrapInfo::slots),
                "[the] harbor trap slots of trap %number%");
        r.number(
                Spec.single("harbor trap extra slots"),
                byTrap(TrapInfo::extraSlots),
                "[the] harbor trap extra slots of trap %number%");
        r.number(
                Spec.single("harbor trap bait"),
                byTrap(TrapInfo::bait),
                "[the] harbor trap bait of trap %number%");
        r.number(
                Spec.single("harbor trap stored"),
                byTrap(TrapInfo::stored),
                "[the] harbor trap stored (count|catches) of trap %number%");
        r.player(
                Spec.single("harbor trap owner"),
                byTrap(t -> Bukkit.getOfflinePlayer(t.owner())),
                "[the] harbor trap owner of trap %number%");
        Ext.expr(
                        r,
                        Location.class,
                        Spec.single("harbor trap location"),
                        byTrap(
                                t -> {
                                    World w = Bukkit.getWorld(t.world());
                                    return w == null ? null : new Location(w, t.x(), t.y(), t.z());
                                }))
                .register("[the] harbor trap location of trap %number%");
        Ext.expr(
                        r,
                        ch.njol.skript.util.Date.class,
                        Spec.single("harbor trap next cycle"),
                        byTrap(
                                t -> {
                                    Instant next = t.nextCycleAt();
                                    return next == null
                                            ? null
                                            : new ch.njol.skript.util.Date(next.toEpochMilli());
                                }))
                .register("[the] harbor trap next cycle [time] of trap %number%");
        r.effect(
                Spec.of("refresh harbor traps"),
                (e, x) -> Args.players(e, x, 0).forEach(p -> TRAPS.refresh(p.getUniqueId())),
                "refresh harbor traps of %players%");
        r.effect(
                Spec.of("buy harbor trap slot"),
                (e, x) -> {
                    Optional<TrapService> t = Harbor.api().service(TrapService.class);
                    Optional<Number> id = Args.number(e, x, 1);
                    if (t.isEmpty() || id.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 0)) {
                        UUID uuid = p.getUniqueId();
                        var _ =
                                t.get()
                                        .purchaseSlot(p, id.get().longValue())
                                        .whenComplete((res, err) -> TRAPS.refresh(uuid));
                    }
                },
                "make %players% buy [a] harbor trap slot for trap %number%");
    }

    // ------------------------------------------------------------------ tension minigame

    private static Optional<ScriptTension> tension(Player p) {
        ScriptTension t = TENSIONS.get(p.getUniqueId());
        if (t != null && !t.session().isActive()) {
            TENSIONS.remove(p.getUniqueId());
            return Optional.empty();
        }
        return Optional.ofNullable(t);
    }

    private static void fireTensionEnd(UUID uuid, String tag, MinigameResult result) {
        Runnable fire =
                () -> {
                    TENSIONS.remove(uuid);
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        Bukkit.getPluginManager()
                                .callEvent(
                                        new ScriptTensionEndEvent(
                                                p, result.name().toLowerCase(Locale.ROOT), tag));
                    }
                };
        if (Bukkit.isPrimaryThread()) {
            fire.run();
            return;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Harbor-Skript");
        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, fire);
        }
    }

    private static void registerTension(Registrar r) {
        r.effect(
                Spec.of("start harbor tension"),
                (e, x) -> {
                    Optional<FishingService> f = fishing();
                    if (f.isEmpty()) {
                        return;
                    }
                    Component title =
                            MiniMessage.miniMessage()
                                    .deserialize(Args.string(e, x, 1).orElse("<gray>???"));
                    Grade g = Args.string(e, x, 2).flatMap(FishingSyntax::grade).orElse(Grade.RARE);
                    double hp = Args.number(e, x, 3).map(Number::doubleValue).orElse(0.0);
                    String tag = Args.string(e, x, 4).orElse("");
                    TensionSpec spec = new TensionSpec(title, g, hp, 1, 0, true, true);
                    for (Player p : Args.players(e, x, 0)) {
                        UUID uuid = p.getUniqueId();
                        Optional<TensionSession> s = f.get().openTension(p, spec);
                        if (s.isPresent()) {
                            TENSIONS.put(uuid, new ScriptTension(s.get(), tag));
                            s.get().onEnd(res -> fireTensionEnd(uuid, tag, res));
                        }
                    }
                },
                "start [a] harbor tension [minigame] for %players% [titled %-string%] [with grade"
                        + " %-string%] [with hp %-number%] [with tag %-string%]");
        r.condition(
                Spec.of("in harbor tension"),
                (e, x) -> Args.all(e, x, p -> tension(p).isPresent()),
                "%players% (is|are) in [a] harbor tension [minigame]",
                "%players% (isn't|is not|aren't|are not) in [a] harbor tension [minigame]");
        Ext.expr(
                        r,
                        Number.class,
                        Spec.of("harbor tension"),
                        (e, x) ->
                                Args.perPlayer(
                                        e,
                                        x,
                                        p ->
                                                tension(p)
                                                        .map(t -> (Number) t.session().tension())
                                                        .orElse(null)))
                .valueChanger(
                        Number.class,
                        (e, x, mode, v) -> {
                            double d = v instanceof Number n ? n.doubleValue() : 0;
                            for (Player p : Args.players(e, x, 0)) {
                                tension(p)
                                        .ifPresent(
                                                t -> {
                                                    TensionSession s = t.session();
                                                    double delta =
                                                            switch (mode) {
                                                                case ADD -> d;
                                                                case REMOVE -> -d;
                                                                case SET -> d - s.tension();
                                                                default -> 0;
                                                            };
                                                    s.addTension(delta);
                                                });
                            }
                        },
                        ChangeMode.ADD,
                        ChangeMode.REMOVE,
                        ChangeMode.SET)
                .register("[the] harbor tension of %players%");
        Ext.expr(
                        r,
                        Number.class,
                        Spec.of("harbor tension hp"),
                        (e, x) ->
                                Args.perPlayer(
                                        e,
                                        x,
                                        p ->
                                                tension(p)
                                                        .map(t -> (Number) t.session().hpRatio())
                                                        .orElse(null)))
                .valueChanger(
                        Number.class,
                        (e, x, mode, v) -> {
                            double d = v instanceof Number n ? n.doubleValue() : 0;
                            for (Player p : Args.players(e, x, 0)) {
                                tension(p).ifPresent(t -> t.session().damage(d));
                            }
                        },
                        ChangeMode.REMOVE)
                .register("[the] harbor tension (hp|health) of %players%");
        r.number(
                Spec.of("harbor tension phase"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        tension(p)
                                                .map(t -> (Number) t.session().phase())
                                                .orElse(null)),
                "[the] harbor tension phase of %players%");
        r.effect(
                Spec.of("stop harbor tension"),
                (e, x) -> {
                    MinigameResult result =
                            Args.string(e, x, 1)
                                    .flatMap(
                                            s -> {
                                                try {
                                                    return Optional.of(
                                                            MinigameResult.valueOf(
                                                                    s.strip()
                                                                            .replace(' ', '_')
                                                                            .toUpperCase(
                                                                                    Locale.ROOT)));
                                                } catch (IllegalArgumentException ex) {
                                                    return Optional.empty();
                                                }
                                            })
                                    .orElse(MinigameResult.ESCAPED);
                    for (Player p : Args.players(e, x, 0)) {
                        tension(p).ifPresent(t -> t.session().close(result));
                    }
                },
                "stop harbor tension of %players% [with result %-string%]");
        r.event(
                "Harbor Tension End",
                ScriptTensionEndEvent.class,
                "스크립트로 시작한 장력 미니게임이 끝난 뒤 (event-player, event-string = 결과 caught·line_broke·escaped·aborted)",
                "harbor tension end");
        r.value(ScriptTensionEndEvent.class, String.class, ScriptTensionEndEvent::result);
        r.text(
                Spec.in("harbor tension tag", ScriptTensionEndEvent.class),
                in(ScriptTensionEndEvent.class, ScriptTensionEndEvent::tag),
                "[the] harbor tension tag");
    }

    // ------------------------------------------------------------------ events

    private static void registerEvents(Registrar r) {
        // Shared: species of the current Fishing event
        r.text(
                Spec.single("harbor event fish"),
                (e, x) -> {
                    FishDef f = eventFish(e);
                    return f == null ? List.of() : List.of(f.id().toString());
                },
                "[the] harbor event fish [id]");
        r.text(
                Spec.single("harbor event fish name"),
                (e, x) -> {
                    FishDef f = eventFish(e);
                    return f == null ? List.of() : List.of(f.name());
                },
                "[the] harbor event fish name");
        r.text(
                Spec.single("harbor event fish grade"),
                (e, x) -> {
                    FishDef f = eventFish(e);
                    return f == null ? List.of() : List.of(f.grade().key());
                },
                "[the] harbor event fish grade");

        // Cast
        r.event(
                "Harbor Fishing Cast",
                FishingCastEvent.class,
                "찌가 기다리기 시작할 때 (event-player, event-string = 굴린 어종 ID, event-number = 입질까지 틱, event-location = 찌 위치)",
                "harbor fishing cast",
                "harbor cast");
        r.value(FishingCastEvent.class, String.class, c -> c.fish().id().toString());
        r.value(FishingCastEvent.class, Number.class, FishingCastEvent::waitTicks);
        r.value(FishingCastEvent.class, Location.class, FishingCastEvent::hook);
        r.text(
                Spec.in("harbor cast region", FishingCastEvent.class),
                in(FishingCastEvent.class, c -> c.region().toString()),
                "[the] harbor cast region");
        r.text(
                Spec.in("harbor cast bait", FishingCastEvent.class),
                in(FishingCastEvent.class, c -> c.bait() == null ? null : String.valueOf(c.bait())),
                "[the] harbor cast bait");
        r.number(
                Spec.in("harbor cast wait", FishingCastEvent.class),
                in(FishingCastEvent.class, FishingCastEvent::waitTicks),
                "[the] harbor cast wait [ticks]");
        r.condition(
                Spec.in("harbor cast school", FishingCastEvent.class),
                (e, x) -> e instanceof FishingCastEvent c && c.school(),
                "[the] harbor cast is over a [fish] school",
                "[the] harbor cast (isn't|is not) over a [fish] school");

        // Roll (cancellable; the fish can be replaced)
        r.event(
                "Harbor Fish Roll",
                FishRollEvent.class,
                "어종을 굴린 직후 (event-player, event-string = 어종 ID). cancel event로 취소, harbor rolled fish로 바꾸기",
                "harbor fish roll");
        r.value(FishRollEvent.class, String.class, f -> f.fish().id().toString());
        Ext.expr(
                        r,
                        String.class,
                        Spec.in("harbor rolled fish", FishRollEvent.class),
                        in(FishRollEvent.class, f -> f.fish().id().toString()))
                .valueChanger(
                        String.class,
                        (e, x, mode, v) -> {
                            if (e instanceof FishRollEvent roll && v != null) {
                                fishDef(String.valueOf(v)).ifPresent(roll::setFish);
                            }
                        },
                        ChangeMode.SET)
                .register("[the] harbor rolled fish [id]");
        r.text(
                Spec.in("harbor roll bait", FishRollEvent.class),
                in(FishRollEvent.class, f -> f.bait() == null ? null : String.valueOf(f.bait())),
                "[the] harbor roll bait");

        // Bite
        r.event(
                "Harbor Fish Bite",
                FishBiteEvent.class,
                "입질이 시작될 때 (event-player, event-string = 어종 ID)",
                "harbor fish bite",
                "harbor bite");
        r.value(FishBiteEvent.class, String.class, b -> b.fish().id().toString());

        // Size / quality roll (changeable)
        r.event(
                "Harbor Quality Roll",
                QualityRolledEvent.class,
                "크기·품질을 정하기 직전 (event-player, event-string = 어종 ID, event-number = 크기 cm). harbor rolled size·quality로 바꾸기",
                "harbor quality roll",
                "harbor size roll");
        r.value(QualityRolledEvent.class, String.class, q -> q.fish().id().toString());
        r.value(QualityRolledEvent.class, Number.class, QualityRolledEvent::sizeCm);
        Ext.expr(
                        r,
                        Number.class,
                        Spec.in("harbor rolled size", QualityRolledEvent.class),
                        in(QualityRolledEvent.class, QualityRolledEvent::sizeCm))
                .valueChanger(
                        Number.class,
                        (e, x, mode, v) -> {
                            if (e instanceof QualityRolledEvent q && v instanceof Number n) {
                                double d = n.doubleValue();
                                double next =
                                        switch (mode) {
                                            case ADD -> q.sizeCm() + d;
                                            case REMOVE -> q.sizeCm() - d;
                                            default -> d;
                                        };
                                q.setSizeCm(Math.max(0.1, next));
                            }
                        },
                        ChangeMode.SET,
                        ChangeMode.ADD,
                        ChangeMode.REMOVE)
                .register("[the] harbor rolled size");
        Ext.expr(
                        r,
                        Number.class,
                        Spec.in("harbor rolled quality", QualityRolledEvent.class),
                        in(QualityRolledEvent.class, q -> q.quality().stars()))
                .valueChanger(
                        Number.class,
                        (e, x, mode, v) -> {
                            if (e instanceof QualityRolledEvent q && v instanceof Number n) {
                                int d = n.intValue();
                                int next =
                                        switch (mode) {
                                            case ADD -> q.quality().stars() + d;
                                            case REMOVE -> q.quality().stars() - d;
                                            default -> d;
                                        };
                                q.setQuality(
                                        Quality.of(Math.clamp(next, Quality.MIN, Quality.MAX)));
                            }
                        },
                        ChangeMode.SET,
                        ChangeMode.ADD,
                        ChangeMode.REMOVE)
                .register("[the] harbor rolled quality");
        r.condition(
                Spec.in("harbor rolled capped", QualityRolledEvent.class),
                (e, x) -> e instanceof QualityRolledEvent q && q.capped(),
                "[the] harbor rolled quality is capped",
                "[the] harbor rolled quality (isn't|is not) capped");

        // Minigame end
        r.event(
                "Harbor Minigame End",
                MinigameEndEvent.class,
                "미니게임이 끝났을 때 (event-player, event-string = 결과 caught·line_broke·escaped·aborted, event-number = 걸린 틱)",
                "harbor minigame end");
        r.value(
                MinigameEndEvent.class,
                String.class,
                m -> m.result().name().toLowerCase(Locale.ROOT));
        r.value(MinigameEndEvent.class, Number.class, MinigameEndEvent::ticks);
        r.text(
                Spec.in("harbor minigame result", MinigameEndEvent.class),
                in(MinigameEndEvent.class, m -> m.result().name().toLowerCase(Locale.ROOT)),
                "[the] harbor minigame result");
        r.number(
                Spec.in("harbor minigame ticks", MinigameEndEvent.class),
                in(MinigameEndEvent.class, MinigameEndEvent::ticks),
                "[the] harbor minigame (ticks|time)");

        // Session end (before the minigame)
        r.event(
                "Harbor Fishing Session End",
                FishingSessionEndEvent.class,
                "미니게임 전에 낚시가 끝났을 때 (event-player, event-string = 이유 reeled_early·missed_bite·bait_changed·interrupted)",
                "harbor fishing session end");
        r.value(
                FishingSessionEndEvent.class,
                String.class,
                s -> s.reason().name().toLowerCase(Locale.ROOT));
        r.text(
                Spec.in("harbor session end reason", FishingSessionEndEvent.class),
                in(FishingSessionEndEvent.class, s -> s.reason().name().toLowerCase(Locale.ROOT)),
                "[the] harbor session end reason");

        // Catch
        r.event(
                "Harbor Fish Catch",
                FishCaughtEvent.class,
                "물고기를 낚은 뒤 (event-player, event-string = 어종 ID, event-number = 크기 cm)",
                "harbor fish catch",
                "harbor catch");
        r.value(FishCaughtEvent.class, String.class, e -> e.fish().id().id().toString());
        r.value(FishCaughtEvent.class, Number.class, e -> e.item().sizeCm());
        r.text(
                Spec.in("harbor caught fish", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.fish().id().id().toString()),
                "[the] harbor caught fish [id]");
        r.text(
                Spec.in("harbor caught fish name", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.fish().name()),
                "[the] harbor caught fish name");
        r.text(
                Spec.in("harbor catch grade", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.fish().grade().key()),
                "[the] harbor catch grade");
        r.text(
                Spec.in("harbor catch region", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.region().id().toString()),
                "[the] harbor catch region");
        r.number(
                Spec.in("harbor catch size", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.item().sizeCm()),
                "[the] harbor catch size");
        r.number(
                Spec.in("harbor catch quality", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.item().quality().stars()),
                "[the] harbor catch quality");
        r.text(
                Spec.in("harbor catch source", FishCaughtEvent.class),
                in(FishCaughtEvent.class, c -> c.source().name().toLowerCase(Locale.ROOT)),
                "[the] harbor catch source");
        r.text(
                Spec.in("harbor catch uid", FishCaughtEvent.class),
                in(
                        FishCaughtEvent.class,
                        c -> c.item().uid() == null ? null : String.valueOf(c.item().uid())),
                "[the] harbor catch (uid|unique id)");
        r.condition(
                Spec.in("harbor first catch", FishCaughtEvent.class),
                (e, x) -> e instanceof FishCaughtEvent c && c.firstCatch(),
                "[the] harbor catch is [a] first catch",
                "[the] harbor catch (isn't|is not) [a] first catch");
        r.condition(
                Spec.in("harbor catch released", FishCaughtEvent.class),
                (e, x) -> e instanceof FishCaughtEvent c && c.released(),
                "[the] harbor catch is released",
                "[the] harbor catch (isn't|is not) released");
        r.condition(
                Spec.in("harbor catch capped", FishCaughtEvent.class),
                (e, x) -> e instanceof FishCaughtEvent c && c.capped(),
                "[the] harbor catch is capped",
                "[the] harbor catch (isn't|is not) capped");

        // Trap harvest
        r.event(
                "Harbor Trap Harvest",
                TrapHarvestEvent.class,
                "통발을 수확한 뒤 (event-player, event-number = 통발 ID, event-string = 지역 ID)",
                "harbor trap harvest");
        r.value(TrapHarvestEvent.class, Number.class, TrapHarvestEvent::trapId);
        r.value(TrapHarvestEvent.class, String.class, t -> t.region().toString());
        Ext.expr(
                        r,
                        String.class,
                        Spec.in("harbor harvested fish", TrapHarvestEvent.class),
                        (e, x) ->
                                e instanceof TrapHarvestEvent t
                                        ? t.fish().stream()
                                                .map(f -> f.species().toString())
                                                .toList()
                                        : List.of())
                .list()
                .register("[all] [the] harbor harvested fish");
        r.number(
                Spec.in("harbor harvested count", TrapHarvestEvent.class),
                in(TrapHarvestEvent.class, t -> t.fish().size()),
                "[the] (number|amount) of harbor harvested fish",
                "[the] harbor harvested count");
        r.number(
                Spec.in("harbor harvest trap", TrapHarvestEvent.class),
                in(TrapHarvestEvent.class, TrapHarvestEvent::trapId),
                "[the] harbor harvest trap [id]");
    }
}
