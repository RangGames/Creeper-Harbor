package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.util.Timespan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;
import wiki.creeper.harbor.wildlife.api.WildFish;
import wiki.creeper.harbor.wildlife.api.WildHit;
import wiki.creeper.harbor.wildlife.api.WildlifeService;
import wiki.creeper.harbor.wildlife.api.event.WildFishSpawnEvent;
import wiki.creeper.harbor.wildlife.api.event.WildFishStartleEvent;

/**
 * When Harbor-Wildlife is enabled: wild fish queries (count, nearby, targeted, per-fish details),
 * scripted spawns (single fish, path schools), removal, density boosts, and the spawn · startle
 * events.
 *
 * <p>Wild fish are virtual objects, not entities, so scripts refer to them by their fish ID (a UUID
 * text). Query results are as of the last simulation step (up to 100ms old); spawns and removals
 * apply on the next step.
 */
public final class WildlifeSyntax {

    private WildlifeSyntax() {}

    /** Last fish or school ID returned by a spawn effect (main thread only). */
    private static @Nullable String lastSpawned;

    private static Optional<WildlifeService> wildlife() {
        return Harbor.api().service(WildlifeService.class);
    }

    private static Optional<FishId> fishId(String raw) {
        return Ids.parse(raw, FishId.PREFIX).map(FishId::new);
    }

    private static Optional<UUID> uuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> strings(Event e, Expression<?>[] x, int index) {
        List<String> out = new ArrayList<>();
        if (index >= x.length || x[index] == null) {
            return out;
        }
        for (Object o : x[index].getArray(e)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static List<Location> locations(Event e, Expression<?>[] x, int index) {
        List<Location> out = new ArrayList<>();
        if (index >= x.length || x[index] == null) {
            return out;
        }
        for (Object o : x[index].getArray(e)) {
            if (o instanceof Location l) {
                out.add(l);
            }
        }
        return out;
    }

    /** One value per wild fish ID of argument 0 (unknown or gone fish are skipped). */
    private static <T> Spec.Getter<T> perFish(Function<WildFish, @Nullable T> value) {
        return (e, x) -> {
            Optional<WildlifeService> w = wildlife();
            List<T> out = new ArrayList<>();
            if (w.isEmpty()) {
                return out;
            }
            for (String raw : strings(e, x, 0)) {
                Optional<WildFish> f = uuid(raw).flatMap(w.get()::fish);
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

    private static <E extends Event, V> Spec.Getter<V> in(
            Class<E> type, Function<E, @Nullable V> value) {
        return (e, x) -> type.isInstance(e) ? Args.one(value.apply(type.cast(e))) : List.of();
    }

    public static void register(Registrar r) {
        registerQueries(r);
        registerActions(r);
        registerEvents(r);
    }

    // ------------------------------------------------------------------ queries

    private static void registerQueries(Registrar r) {
        r.number(
                Spec.single("harbor wild fish count"),
                (e, x) -> wildlife().map(w -> List.<Number>of(w.count())).orElse(List.of()),
                "[the] harbor wild fish count",
                "[the] (number|amount) of harbor wild fish");
        Ext.expr(
                        r,
                        String.class,
                        Spec.single("harbor wild fish near"),
                        (e, x) -> {
                            Optional<WildlifeService> w = wildlife();
                            if (!(Args.single(e, x, 0) instanceof Location at) || w.isEmpty()) {
                                return List.of();
                            }
                            double radius =
                                    Args.number(e, x, 1).map(Number::doubleValue).orElse(16.0);
                            return w.get().nearby(at, radius).stream()
                                    .map(f -> f.id().toString())
                                    .toList();
                        })
                .list()
                .register(
                        "[all] [the] harbor wild fish (near|around) %location% [within %-number%"
                                + " [blocks]]");
        r.text(
                Spec.single("harbor wild fish targeted"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    if (!(Args.single(e, x, 0) instanceof Player p) || w.isEmpty()) {
                        return List.of();
                    }
                    double distance = Args.number(e, x, 1).map(Number::doubleValue).orElse(32.0);
                    Location eye = p.getEyeLocation();
                    return w.get()
                            .raycast(eye, eye.getDirection(), distance)
                            .map(WildHit::fish)
                            .map(f -> List.of(f.id().toString()))
                            .orElse(List.of());
                },
                "[the] harbor wild fish targeted by %player% [within %-number% [blocks]]");
        r.condition(
                Spec.of("harbor wild fish exists"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    List<String> ids = strings(e, x, 0);
                    return w.isPresent()
                            && !ids.isEmpty()
                            && ids.stream()
                                    .allMatch(s -> uuid(s).flatMap(w.get()::fish).isPresent());
                },
                "%strings% (is|are) [a] harbor wild fish",
                "%strings% (isn't|is not|aren't|are not) [a] harbor wild fish");
        r.condition(
                Spec.of("harbor wild fish visible"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    List<String> ids = strings(e, x, 0);
                    return w.isPresent()
                            && !ids.isEmpty()
                            && ids.stream()
                                    .allMatch(
                                            s ->
                                                    uuid(s).flatMap(w.get()::fish)
                                                            .map(WildFish::visible)
                                                            .orElse(false));
                },
                "harbor wild fish %strings% (is|are) visible",
                "harbor wild fish %strings% (isn't|is not|aren't|are not) visible");
        r.text(
                Spec.of("harbor wild fish species"),
                perFish(f -> f.fish().toString()),
                "[the] harbor wild (fish|species) of %strings%");
        r.text(
                Spec.of("harbor wild species entry"),
                perFish(f -> f.species().toString()),
                "[the] harbor wild species entry of %strings%");
        Ext.expr(r, Location.class, Spec.of("harbor wild location"), perFish(WildFish::location))
                .register("[the] harbor wild location of %strings%");
        r.text(
                Spec.of("harbor wild state"),
                perFish(f -> f.state().name().toLowerCase(Locale.ROOT)),
                "[the] harbor wild state of %strings%");
        r.text(
                Spec.of("harbor wild medium"),
                perFish(f -> f.medium().key()),
                "[the] harbor wild medium of %strings%");
        r.text(
                Spec.of("harbor wild school"),
                perFish(f -> f.school() == null ? null : String.valueOf(f.school())),
                "[the] harbor wild school of %strings%");
        r.number(
                Spec.of("harbor wild radius"),
                perFish(WildFish::radius),
                "[the] harbor wild radius of %strings%");
        r.condition(
                Spec.of("harbor wild scripted"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    List<String> ids = strings(e, x, 0);
                    return w.isPresent()
                            && !ids.isEmpty()
                            && ids.stream()
                                    .allMatch(
                                            s ->
                                                    uuid(s).flatMap(w.get()::fish)
                                                            .map(WildFish::scripted)
                                                            .orElse(false));
                },
                "harbor wild fish %strings% (is|are) scripted",
                "harbor wild fish %strings% (isn't|is not|aren't|are not) scripted");
    }

    // ------------------------------------------------------------------ spawn · remove · boost

    private static void registerActions(Registrar r) {
        r.effect(
                Spec.single("spawn harbor wild fish"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(WildlifeSyntax::fishId);
                    if (w.isEmpty() || fish.isEmpty()) {
                        return;
                    }
                    Optional<String> action = Args.string(e, x, 2);
                    Optional<Number> seconds = Args.number(e, x, 3);
                    ScriptedBehaviour behaviour =
                            action.isEmpty() && seconds.isEmpty()
                                    ? ScriptedBehaviour.CRUISE
                                    : ScriptedBehaviour.show(
                                            action.orElse(null),
                                            seconds.map(Number::intValue).orElse(0));
                    lastSpawned = null;
                    for (Location at : locations(e, x, 1)) {
                        w.get()
                                .spawnScripted(fish.get(), at, behaviour)
                                .ifPresent(id -> lastSpawned = id.toString());
                    }
                },
                "spawn [a] harbor wild fish %string% at %locations% [with action %-string%] [for"
                        + " %-number% seconds]");
        r.effect(
                Spec.single("spawn harbor wild school"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(WildlifeSyntax::fishId);
                    List<Location> path = locations(e, x, 1);
                    if (w.isEmpty() || fish.isEmpty() || path.isEmpty()) {
                        return;
                    }
                    int count = Args.number(e, x, 2).map(Number::intValue).orElse(0);
                    lastSpawned =
                            w.get()
                                    .spawnSchool(fish.get(), path, count)
                                    .map(UUID::toString)
                                    .orElse(null);
                },
                "spawn [a] harbor wild school of %string% along %locations% [with %-number% fish]");
        r.text(
                Spec.single("last spawned harbor wild fish"),
                (e, x) -> Args.one(lastSpawned),
                "[the] last spawned harbor wild (fish|school)");
        r.effect(
                Spec.single("remove harbor wild fish"),
                (e, x) ->
                        wildlife()
                                .ifPresent(
                                        w ->
                                                strings(e, x, 0)
                                                        .forEach(
                                                                s -> uuid(s).ifPresent(w::remove))),
                "remove harbor wild fish %strings%");
        r.effect(
                Spec.single("boost harbor wild fish"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    Optional<RegionId> region =
                            Args.string(e, x, 0)
                                    .flatMap(s -> Ids.parse(s, RegionId.PREFIX))
                                    .map(RegionId::new);
                    Optional<Number> mult = Args.number(e, x, 1);
                    if (w.isEmpty()
                            || region.isEmpty()
                            || mult.isEmpty()
                            || !(Args.single(e, x, 2) instanceof Timespan span)) {
                        return;
                    }
                    Set<FishId> fish = new HashSet<>();
                    for (String s : strings(e, x, 3)) {
                        fishId(s).ifPresent(fish::add);
                    }
                    String source =
                            Args.string(e, x, 4).orElse("skript:" + region.get().id().name());
                    w.get()
                            .boost(
                                    source,
                                    region.get(),
                                    fish,
                                    mult.get().doubleValue(),
                                    Instant.now()
                                            .plusMillis(
                                                    span.getAs(Timespan.TimePeriod.MILLISECOND)));
                },
                "boost harbor wild fish in [region] %string% by %number% for %timespan% [only"
                        + " %-strings%] [with source %-string%]");
        r.effect(
                Spec.single("clear harbor wild boost"),
                (e, x) -> {
                    Optional<WildlifeService> w = wildlife();
                    Optional<String> source = Args.string(e, x, 0);
                    if (w.isPresent() && source.isPresent()) {
                        w.get().clearBoost(source.get());
                    }
                },
                "clear harbor wild boost %string%");
    }

    // ------------------------------------------------------------------ events

    private static void registerEvents(Registrar r) {
        r.event(
                "Harbor Wild Fish Spawn",
                WildFishSpawnEvent.class,
                "야생 물고기(무리)가 생기기 직전, cancel event로 막기 (event-location, event-string = 어종 ID, event-number = 마릿수)",
                "harbor wild fish spawn");
        r.value(WildFishSpawnEvent.class, Location.class, WildFishSpawnEvent::location);
        r.value(WildFishSpawnEvent.class, String.class, s -> s.fish().toString());
        r.value(WildFishSpawnEvent.class, Number.class, WildFishSpawnEvent::count);
        r.text(
                Spec.in("harbor wild spawn reason", WildFishSpawnEvent.class),
                in(WildFishSpawnEvent.class, s -> s.reason().name().toLowerCase(Locale.ROOT)),
                "[the] harbor wild spawn reason");
        r.text(
                Spec.in("harbor wild spawn medium", WildFishSpawnEvent.class),
                in(WildFishSpawnEvent.class, s -> s.medium().key()),
                "[the] harbor wild spawn medium");

        r.event(
                "Harbor Wild Fish Startle",
                WildFishStartleEvent.class,
                "플레이어 때문에 야생 물고기가 놀라 달아난 뒤 (event-player, event-string = 어종 ID, event-location = 물고기 위치)",
                "harbor wild fish startle");
        r.value(WildFishStartleEvent.class, String.class, s -> s.fish().fish().toString());
        r.value(WildFishStartleEvent.class, Location.class, s -> s.fish().location());
        r.text(
                Spec.in("harbor startled wild fish", WildFishStartleEvent.class),
                in(WildFishStartleEvent.class, s -> s.fish().id().toString()),
                "[the] harbor startled wild fish");
    }
}
