package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.lang.Expression;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRecord;
import wiki.creeper.harbor.boss.api.BossRecordService;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.BossService;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.api.WorldBossService;
import wiki.creeper.harbor.boss.api.WorldBossStatus;
import wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent;
import wiki.creeper.harbor.boss.api.event.BossEncounterStartEvent;
import wiki.creeper.harbor.boss.api.event.BossPhaseChangeEvent;
import wiki.creeper.harbor.boss.api.event.WorldBossProgressEvent;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.PartyService;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Boss is enabled: boss definitions, the encounter a player is in (boss, phase, HP,
 * hull, participants, roles), admin forced start and end, world boss status and start, fastest kill
 * records (DB, {@link AsyncCache}), parties (Core PartyService, implemented by Boss), and all boss
 * api events with per-participant contribution in the end event.
 */
public final class BossSyntax {

    private BossSyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<BossId> boss(String raw) {
        try {
            return Ids.parse(raw, BossId.PREFIX).map(BossId::new);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<BossId> bossArg(Event e, Expression<?>[] x, int index) {
        return Args.string(e, x, index).flatMap(BossSyntax::boss);
    }

    private static OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** Records: key = boss ID + "|" + season (0 = all seasons). */
    private static final AsyncCache<String, List<BossRecord>> RECORDS =
            AsyncCache.ttlFromBalance(
                    k -> {
                        int bar = k.indexOf('|');
                        BossId id = BossId.parse(k.substring(0, bar));
                        int season = Integer.parseInt(k.substring(bar + 1));
                        BossRecordService s = service(BossRecordService.class).orElseThrow();
                        // Top 10 is what boss_records keeps per season
                        return season <= 0 ? s.allTime(id, 10) : s.top(id, season, 10);
                    });

    public static void register(Registrar r) {
        definitions(r);
        encounters(r);
        worldBoss(r);
        records(r);
        parties(r);
        events(r);
        weeklyRewards(r);
    }

    /** This week's boss reward count (remaining, used, limit). Empty for story bosses. */
    private static void weeklyRewards(Registrar r) {
        wiki.creeper.harbor.skript.lambda.Ext.await(
                r,
                "harbor load weekly boss rewards",
                new int[] {2, 3, 4},
                c -> {
                    Optional<BossService> s = service(BossService.class);
                    Optional<OfflinePlayer> p = c.offlinePlayer(0);
                    Optional<BossId> id = c.string(1).flatMap(BossSyntax::boss);
                    if (s.isEmpty() || p.isEmpty() || id.isEmpty()) {
                        return null;
                    }
                    return s.get()
                            .weeklyRewards(p.get().getUniqueId(), id.get())
                            .thenApply(
                                    w ->
                                            w.map(
                                                            x ->
                                                                    new List<?>[] {
                                                                        List.of(x.remaining()),
                                                                        List.of(x.used()),
                                                                        List.of(x.limit())
                                                                    })
                                                    .orElseGet(
                                                            () ->
                                                                    new List<?>[] {
                                                                        List.of(), List.of(),
                                                                        List.of()
                                                                    }));
                },
                "harbor load weekly boss rewards [left] of %offlineplayer% for [boss] %string%"
                        + " into %objects% [with used count in %-objects%] [with limit in"
                        + " %-objects%]");
    }

    // ------------------------------------------------------------------ definitions

    private static void definitions(Registrar r) {
        r.text(
                Spec.single("harbor boss ids"),
                (e, x) ->
                        service(BossService.class)
                                .map(
                                        s ->
                                                s.definitions().stream()
                                                        .map(d -> d.id().toString())
                                                        .sorted()
                                                        .toList())
                                .orElse(List.of()),
                "[all] [the] harbor bosses",
                "[all] [the] harbor boss ids");
        r.text(
                Spec.single("harbor boss name"),
                (e, x) -> {
                    Optional<BossId> id = bossArg(e, x, 0);
                    Optional<BossService> s = service(BossService.class);
                    if (id.isEmpty() || s.isEmpty()) {
                        return List.of();
                    }
                    return s.get()
                            .definition(id.get())
                            .map(d -> List.of(d.name()))
                            .orElse(List.of());
                },
                "[the] harbor boss name of %string%");
        r.text(
                Spec.single("harbor boss type"),
                (e, x) -> {
                    Optional<BossId> id = bossArg(e, x, 0);
                    Optional<BossService> s = service(BossService.class);
                    if (id.isEmpty() || s.isEmpty()) {
                        return List.of();
                    }
                    return s.get()
                            .definition(id.get())
                            .map(d -> List.of(d.type().key()))
                            .orElse(List.of());
                },
                "[the] harbor boss type of %string%");
    }

    // ------------------------------------------------------------------ encounters

    private static Optional<EncounterInfo> encounter(Player p) {
        return service(BossService.class).flatMap(s -> s.encounterOf(p.getUniqueId()));
    }

    private static void encounterText(
            Registrar r, String field, Function<EncounterInfo, String> f) {
        r.text(
                Spec.of("harbor boss " + field),
                (e, x) -> Args.perPlayer(e, x, p -> encounter(p).map(f).orElse(null)),
                "[the] harbor boss " + field + " of %players%");
    }

    private static void encounterNumber(
            Registrar r, String field, Function<EncounterInfo, Number> f) {
        r.number(
                Spec.of("harbor boss " + field),
                (e, x) -> Args.perPlayer(e, x, p -> encounter(p).map(f).orElse(null)),
                "[the] harbor boss " + field + " of %players%");
    }

    private static void encounters(Registrar r) {
        r.condition(
                Spec.of("in harbor boss fight"),
                (e, x) ->
                        service(BossService.class)
                                .map(s -> Args.all(e, x, p -> s.inEncounter(p.getUniqueId())))
                                .orElse(false),
                "%players% (is|are) in [a] harbor boss fight",
                "%players% (isn't|is not|aren't|are not) in [a] harbor boss fight");
        r.text(
                Spec.of("harbor boss of"),
                (e, x) ->
                        Args.perPlayer(
                                e, x, p -> encounter(p).map(i -> i.boss().toString()).orElse(null)),
                "[the] harbor boss of %players%",
                "%players%'[s] harbor boss");
        encounterText(r, "encounter id", i -> i.id().toString());
        encounterText(r, "phase", i -> i.state().key());
        encounterText(r, "server", EncounterInfo::server);
        encounterNumber(r, "hp", i -> i.hpRatio() * 100);
        encounterNumber(r, "hull", EncounterInfo::hull);
        encounterNumber(r, "max hull", EncounterInfo::hullMax);
        encounterNumber(r, "slot", EncounterInfo::slot);
        encounterNumber(r, "elapsed seconds", i -> i.elapsedTicks() / 20.0);
        r.text(
                Spec.of("harbor boss role"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        encounter(p)
                                                .map(i -> i.roles().get(p.getUniqueId()))
                                                .map(BossRole::key)
                                                .orElse(null)),
                "[the] harbor boss role of %players%");
        r.player(
                Spec.single("harbor boss participants"),
                (e, x) ->
                        Args.single(e, x, 0) instanceof Player p
                                ? encounter(p)
                                        .map(
                                                i ->
                                                        i.members().stream()
                                                                .map(BossSyntax::offline)
                                                                .toList())
                                        .orElse(List.of())
                                : List.of(),
                "[the] harbor boss (participants|members) of %player%");
        r.player(
                Spec.single("harbor boss leader"),
                (e, x) ->
                        Args.single(e, x, 0) instanceof Player p
                                ? encounter(p)
                                        .map(i -> List.of(offline(i.leader())))
                                        .orElse(List.of())
                                : List.of(),
                "[the] harbor boss leader of %player%");
        r.text(
                Spec.single("harbor active boss encounters"),
                (e, x) ->
                        service(BossService.class)
                                .map(
                                        s ->
                                                s.encounters().stream()
                                                        .map(i -> i.id().toString())
                                                        .toList())
                                .orElse(List.of()),
                "[all] [the] active harbor boss encounters");
        r.effect(
                Spec.single("admin force start harbor boss"),
                (e, x) -> {
                    Optional<BossId> id = bossArg(e, x, 0);
                    Optional<BossService> s = service(BossService.class);
                    if (id.isPresent()
                            && s.isPresent()
                            && Args.single(e, x, 1) instanceof Player leader) {
                        BossService.StartResult result = s.get().forceStart(id.get(), leader);
                        if (result != BossService.StartResult.STARTED) {
                            ch.njol.skript.Skript.warning("harbor boss 강제 시작 실패: " + key(result));
                        }
                    }
                },
                "harbor admin (start|force start) boss %string% (with|for) [leader] %player%");
        for (EncounterState result :
                List.of(EncounterState.CLEARED, EncounterState.FAILED, EncounterState.ABORTED)) {
            r.effect(
                    Spec.of("admin force end harbor boss " + result.key()),
                    (e, x) -> {
                        Optional<BossService> s = service(BossService.class);
                        if (s.isEmpty()) {
                            return;
                        }
                        for (Player p : Args.players(e, x, 0)) {
                            encounter(p).ifPresent(i -> s.get().forceEnd(i.id(), result));
                        }
                    },
                    "harbor admin end [the] boss fight of %players% as " + result.key());
        }
    }

    // ------------------------------------------------------------------ world boss

    private static Optional<WorldBossStatus> worldStatus() {
        return service(WorldBossService.class).flatMap(WorldBossService::status);
    }

    private static void worldBoss(Registrar r) {
        r.text(
                Spec.single("harbor world boss"),
                (e, x) -> worldStatus().map(s -> List.of(s.boss().toString())).orElse(List.of()),
                "[the] harbor world boss [id]");
        r.text(
                Spec.single("harbor world boss phase"),
                (e, x) -> worldStatus().map(s -> List.of(s.state().key())).orElse(List.of()),
                "[the] harbor world boss (phase|state)");
        r.text(
                Spec.single("harbor world boss server"),
                (e, x) -> worldStatus().map(s -> List.of(s.server())).orElse(List.of()),
                "[the] harbor world boss server");
        r.number(
                Spec.single("harbor world boss percent"),
                (e, x) -> worldStatus().map(s -> List.<Number>of(s.percent())).orElse(List.of()),
                "[the] harbor world boss (percent|gauge)");
        r.number(
                Spec.single("harbor world boss stage"),
                (e, x) -> worldStatus().map(s -> List.<Number>of(s.stage())).orElse(List.of()),
                "[the] harbor world boss stage");
        r.number(
                Spec.single("harbor world boss participants"),
                (e, x) ->
                        worldStatus().map(s -> List.<Number>of(s.participants())).orElse(List.of()),
                "[the] (number|amount) of harbor world boss participants");
        r.condition(
                Spec.single("harbor world boss running here"),
                (e, x) ->
                        service(WorldBossService.class)
                                .map(WorldBossService::runningHere)
                                .orElse(false),
                "[the] harbor world boss is running [on this server]",
                "[the] harbor world boss (isn't|is not) running [on this server]");
        r.effect(
                Spec.single("admin start harbor world boss"),
                (e, x) -> {
                    Optional<BossId> id = bossArg(e, x, 0);
                    Optional<WorldBossService> s = service(WorldBossService.class);
                    if (id.isPresent() && s.isPresent()) {
                        var _ = s.get().start(id.get());
                    }
                },
                "harbor admin start world boss %string%");
    }

    // ------------------------------------------------------------------ records

    private static void records(Registrar r) {
        r.number(
                Spec.single("harbor boss record time"),
                (e, x) -> recordValue(e, x, b -> b.durationMs() / 1000.0),
                "[the] harbor boss record (time|seconds) of %string% [(at|with) rank %-number%]"
                        + " [in season %-number%]");
        r.player(
                Spec.single("harbor boss record members"),
                (e, x) -> {
                    List<UUID> m =
                            recordValue(e, x, BossRecord::members).stream()
                                    .findFirst()
                                    .orElse(List.of());
                    return m.stream().map(BossSyntax::offline).toList();
                },
                "[the] harbor boss record (members|holders) of %string% [(at|with) rank %-number%]"
                        + " [in season %-number%]");
        r.number(
                Spec.single("harbor boss record count"),
                (e, x) -> {
                    List<BossRecord> list = records(e, x, 1);
                    return list == null ? List.of() : List.of(list.size());
                },
                "[the] (number|amount) of harbor boss records of %string% [in season %-number%]");
    }

    private static @Nullable List<BossRecord> records(Event e, Expression<?>[] x, int seasonIndex) {
        Optional<BossId> id = bossArg(e, x, 0);
        if (id.isEmpty() || service(BossRecordService.class).isEmpty()) {
            return null;
        }
        int season = Args.number(e, x, seasonIndex).map(Number::intValue).orElse(0);
        return RECORDS.get(id.get() + "|" + season);
    }

    private static <V> List<V> recordValue(Event e, Expression<?>[] x, Function<BossRecord, V> f) {
        List<BossRecord> list = records(e, x, 2);
        int rank = Args.number(e, x, 1).map(Number::intValue).orElse(1);
        if (list == null || rank < 1 || rank > list.size()) {
            return List.of();
        }
        return List.of(f.apply(list.get(rank - 1)));
    }

    // ------------------------------------------------------------------ parties

    private static void parties(Registrar r) {
        r.condition(
                Spec.of("in harbor party"),
                (e, x) ->
                        Harbor.api()
                                .parties()
                                .map(
                                        s ->
                                                Args.all(
                                                        e,
                                                        x,
                                                        p ->
                                                                s.partyOf(p.getUniqueId())
                                                                        .isPresent()))
                                .orElse(false),
                "%players% (is|are) in [a] harbor party",
                "%players% (isn't|is not|aren't|are not) in [a] harbor party");
        r.player(
                Spec.single("harbor party members"),
                (e, x) -> {
                    Optional<PartyService> s = Harbor.api().parties();
                    if (s.isEmpty() || !(Args.single(e, x, 0) instanceof OfflinePlayer p)) {
                        return List.of();
                    }
                    return s.get().membersOf(p.getUniqueId()).stream()
                            .map(BossSyntax::offline)
                            .toList();
                },
                "[the] harbor party members of %offlineplayer%");
        r.player(
                Spec.single("harbor party leader"),
                (e, x) -> {
                    Optional<PartyService> s = Harbor.api().parties();
                    if (s.isEmpty() || !(Args.single(e, x, 0) instanceof OfflinePlayer p)) {
                        return List.of();
                    }
                    return s.get()
                            .partyOf(p.getUniqueId())
                            .map(party -> List.of(offline(party.leader())))
                            .orElse(List.of());
                },
                "[the] harbor party leader of %offlineplayer%");
        r.text(
                Spec.of("harbor party role"),
                (e, x) ->
                        Harbor.api()
                                .parties()
                                .map(
                                        s ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                s.partyOf(p.getUniqueId())
                                                                        .flatMap(
                                                                                party ->
                                                                                        party
                                                                                                .members()
                                                                                                .stream()
                                                                                                .filter(
                                                                                                        m ->
                                                                                                                m.player()
                                                                                                                        .equals(
                                                                                                                                p
                                                                                                                                        .getUniqueId()))
                                                                                                .findFirst())
                                                                        .map(m -> key(m.role()))
                                                                        .orElse(null)))
                                .orElse(List.of()),
                "[the] harbor party role of %players%");
        r.condition(
                Spec.single("same harbor party"),
                (e, x) -> {
                    Optional<PartyService> s = Harbor.api().parties();
                    return s.isPresent()
                            && Args.single(e, x, 0) instanceof OfflinePlayer a
                            && Args.single(e, x, 1) instanceof OfflinePlayer b
                            && s.get().sameParty(a.getUniqueId(), b.getUniqueId());
                },
                "%offlineplayer% and %offlineplayer% are in the same harbor party",
                "%offlineplayer% and %offlineplayer% (aren't|are not) in the same harbor party");
    }

    // ------------------------------------------------------------------ api events

    private static @Nullable EncounterInfo eventEncounter(Event e) {
        return switch (e) {
            case BossEncounterStartEvent s -> s.encounter();
            case BossEncounterEndEvent s -> s.encounter();
            case BossPhaseChangeEvent s -> s.encounter();
            default -> null;
        };
    }

    private static void events(Registrar r) {
        r.event(
                "Harbor Boss Start",
                BossEncounterStartEvent.class,
                "보스전 시작 직전 (event-string = 보스 ID, harbor event boss leader · participants)."
                        + " cancel event로 막는다",
                "harbor boss (start|encounter start)");
        r.value(BossEncounterStartEvent.class, String.class, e -> e.encounter().boss().toString());
        r.value(
                BossEncounterStartEvent.class,
                OfflinePlayer.class,
                e -> offline(e.encounter().leader()));
        r.condition(
                Spec.in("harbor boss start forced", BossEncounterStartEvent.class),
                (e, x) -> e instanceof BossEncounterStartEvent s && s.forced(),
                "[the] harbor boss start (is|was) forced",
                "[the] harbor boss start (isn't|is not|wasn't|was not) forced");

        r.event(
                "Harbor Boss End",
                BossEncounterEndEvent.class,
                "보스전이 끝난 뒤, 보상 지급 전 (event-string = 보스 ID, event-number = 걸린 초, harbor boss"
                        + " result · contribution …)",
                "harbor boss (end|encounter end)");
        r.value(BossEncounterEndEvent.class, String.class, e -> e.boss().toString());
        r.value(BossEncounterEndEvent.class, Number.class, e -> e.durationMs() / 1000.0);
        r.value(
                BossEncounterEndEvent.class,
                OfflinePlayer.class,
                e -> offline(e.encounter().leader()));
        r.text(
                Spec.in("harbor boss result", BossEncounterEndEvent.class),
                (e, x) ->
                        e instanceof BossEncounterEndEvent b
                                ? List.of(b.result().key())
                                : List.of(),
                "[the] harbor boss result");
        r.condition(
                Spec.in("harbor boss cleared", BossEncounterEndEvent.class),
                (e, x) -> e instanceof BossEncounterEndEvent b && b.cleared(),
                "[the] harbor boss (was|is) cleared",
                "[the] harbor boss (wasn't|was not|isn't|is not) cleared");
        r.condition(
                Spec.in("harbor boss story", BossEncounterEndEvent.class),
                (e, x) -> e instanceof BossEncounterEndEvent b && b.story(),
                "[the] harbor boss (was|is) a story fight",
                "[the] harbor boss (wasn't|was not|isn't|is not) a story fight");
        r.text(
                Spec.in("harbor boss fish", BossEncounterEndEvent.class),
                (e, x) ->
                        e instanceof BossEncounterEndEvent b
                                ? b.fish().map(f -> List.of(f.toString())).orElse(List.of())
                                : List.of(),
                "[the] harbor boss fish");
        contribution(r, "score", Contribution::score);
        contribution(r, "rank", Contribution::rank);
        contribution(r, "damage", Contribution::damage);
        contribution(r, "tension", Contribution::tension);
        contribution(r, "repair", Contribution::repair);
        contribution(r, "evasion", Contribution::evasion);
        r.text(
                Spec.in("harbor boss contribution role", BossEncounterEndEvent.class),
                (e, x) -> {
                    Contribution c = contributionOf(e, x);
                    return c == null ? List.of() : List.of(c.role().key());
                },
                "[the] harbor boss contribution role of %offlineplayer%");
        r.condition(
                Spec.in("harbor boss top contributor", BossEncounterEndEvent.class),
                (e, x) -> {
                    Contribution c = contributionOf(e, x);
                    return c != null && c.top();
                },
                "%offlineplayer% (is|was) [a] top harbor boss contributor",
                "%offlineplayer% (isn't|is not|wasn't|was not) [a] top harbor boss contributor");
        r.condition(
                Spec.in("harbor boss eligible", BossEncounterEndEvent.class),
                (e, x) -> {
                    Contribution c = contributionOf(e, x);
                    return c != null && c.eligible();
                },
                "%offlineplayer% (is|was) eligible for harbor boss rewards",
                "%offlineplayer% (isn't|is not|wasn't|was not) eligible for harbor boss rewards");

        r.event(
                "Harbor Boss Phase Change",
                BossPhaseChangeEvent.class,
                "보스전 단계가 바뀔 때 (event-string = 새 단계, harbor boss old phase · phase cause · event"
                        + " boss)",
                "harbor boss phase change");
        r.value(BossPhaseChangeEvent.class, String.class, e -> e.to().key());
        r.text(
                Spec.in("harbor boss old phase", BossPhaseChangeEvent.class),
                (e, x) -> e instanceof BossPhaseChangeEvent p ? List.of(p.from().key()) : List.of(),
                "[the] harbor boss old phase");
        r.text(
                Spec.in("harbor boss phase cause", BossPhaseChangeEvent.class),
                (e, x) -> e instanceof BossPhaseChangeEvent p ? List.of(p.cause()) : List.of(),
                "[the] harbor boss phase cause");

        r.text(
                Spec.single("harbor event boss"),
                (e, x) -> {
                    EncounterInfo i = eventEncounter(e);
                    if (i != null) {
                        return List.of(i.boss().toString());
                    }
                    return e instanceof WorldBossProgressEvent w
                            ? List.of(w.status().boss().toString())
                            : List.of();
                },
                "[the] harbor event boss");
        r.text(
                Spec.single("harbor event boss encounter id"),
                (e, x) -> {
                    EncounterInfo i = eventEncounter(e);
                    return i == null ? List.of() : List.of(i.id().toString());
                },
                "[the] harbor event boss encounter id");
        r.player(
                Spec.single("harbor event boss participants"),
                (e, x) -> {
                    EncounterInfo i = eventEncounter(e);
                    return i == null
                            ? List.of()
                            : i.members().stream().map(BossSyntax::offline).toList();
                },
                "[the] harbor event boss (participants|members)");

        r.event(
                "Harbor World Boss Progress",
                WorldBossProgressEvent.class,
                "월드 보스가 25% 단계에 들어선 뒤, 실행 서버에서만 (event-number = 단계 1~3, event-string = 보스" + " ID)",
                "harbor world boss progress");
        r.value(WorldBossProgressEvent.class, Number.class, WorldBossProgressEvent::stage);
        r.value(WorldBossProgressEvent.class, String.class, e -> e.status().boss().toString());
    }

    private static @Nullable Contribution contributionOf(Event e, Expression<?>[] x) {
        if (!(e instanceof BossEncounterEndEvent b)
                || !(Args.single(e, x, 0) instanceof OfflinePlayer p)) {
            return null;
        }
        return b.contribution(p.getUniqueId()).orElse(null);
    }

    private static void contribution(Registrar r, String field, Function<Contribution, Number> f) {
        r.number(
                Spec.in("harbor boss contribution " + field, BossEncounterEndEvent.class),
                (e, x) -> {
                    Contribution c = contributionOf(e, x);
                    return c == null ? List.of() : List.of(f.apply(c));
                },
                "[the] harbor boss contribution " + field + " of %offlineplayer%");
    }
}
