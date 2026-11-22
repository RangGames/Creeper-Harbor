package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.classes.Changer.ChangeMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.quest.api.ChronicleService;
import wiki.creeper.harbor.quest.api.ObjectiveSignal;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry;
import wiki.creeper.harbor.quest.api.QuestRewardSummary;
import wiki.creeper.harbor.quest.api.QuestService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.QuestView;
import wiki.creeper.harbor.quest.api.ServerProgressService;
import wiki.creeper.harbor.quest.api.event.ChapterCompletedEvent;
import wiki.creeper.harbor.quest.api.event.ChroniclePieceEvent;
import wiki.creeper.harbor.quest.api.event.QuestAbandonedEvent;
import wiki.creeper.harbor.quest.api.event.QuestAcceptedEvent;
import wiki.creeper.harbor.quest.api.event.QuestCompletedEvent;
import wiki.creeper.harbor.quest.api.event.QuestProgressEvent;
import wiki.creeper.harbor.quest.api.event.QuestReadyEvent;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;
import wiki.creeper.harbor.skript.lambda.ValueExpr;

/**
 * When Harbor-Quest is enabled: quest state, active quests and their objectives/progress, accept ·
 * abandon · turn in · track, objective signals (scripts advance objectives like another module's
 * type), the chronicle, server progress (chapter clears, climax), and every Quest api event.
 *
 * <p>The type of an objective signal must be an objective type used by quest content (a built-in
 * type or one registered by another module). Scripts do not register new types: the content check
 * runs before Skript, so an unknown type fails the check.
 *
 * <p>Server progress comes from an async API and is eventually consistent ({@link LatestValues}).
 * Admin mutations ({@code harbor admin complete · reset · set ...}) go through the QuestService
 * admin API (same rules as {@code /harbor quest}) and are awaiting effects.
 */
public final class QuestSyntax {

    private QuestSyntax() {}

    private static final LatestValues<Integer, Long> CHAPTER_CLEARS =
            new LatestValues<>(
                    30_000, chapter -> progress().orElseThrow().chapterClearCount(chapter));
    private static final LatestValues<Boolean, ServerProgressService.ClimaxStatus> CLIMAX =
            new LatestValues<>(30_000, k -> progress().orElseThrow().climaxStatus());

    private static Optional<QuestService> quests() {
        return Harbor.api().service(QuestService.class);
    }

    private static Optional<ServerProgressService> progress() {
        return Harbor.api().service(ServerProgressService.class);
    }

    private static Optional<ChronicleService> chronicle() {
        return Harbor.api().service(ChronicleService.class);
    }

    private static Optional<QuestId> quest(String raw) {
        return Ids.parse(raw, QuestId.PREFIX).map(QuestId::new);
    }

    private static <E extends Event, V> Spec.Getter<V> in(
            Class<E> type, Function<E, @Nullable V> value) {
        return (e, x) -> type.isInstance(e) ? Args.one(value.apply(type.cast(e))) : List.of();
    }

    /** The active quest view of a player (empty if the quest is not in progress). */
    private static Optional<QuestView> view(QuestService s, Player p, QuestId q) {
        return s.active(p.getUniqueId()).stream().filter(v -> v.quest().equals(q)).findFirst();
    }

    /** Value from the active quest view in argument 1, per player of argument 0. */
    private static <T> Spec.Getter<T> perView(Function<QuestView, @Nullable T> value) {
        return (e, x) -> {
            Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
            Optional<QuestService> s = quests();
            if (q.isEmpty() || s.isEmpty()) {
                return List.of();
            }
            return Args.perPlayer(e, x, p -> view(s.get(), p, q.get()).map(value).orElse(null));
        };
    }

    private static <T> Spec.Getter<T> climax(
            Function<ServerProgressService.ClimaxStatus, @Nullable T> value) {
        return (e, x) -> CLIMAX.get(true).map(c -> Args.one(value.apply(c))).orElse(List.of());
    }

    private static <T> Spec.Getter<T> rewards(Function<QuestRewardSummary, @Nullable T> value) {
        return in(QuestCompletedEvent.class, c -> value.apply(c.rewards()));
    }

    public static void register(Registrar r) {
        registerState(r);
        registerActions(r);
        registerAdmin(r);
        registerBoard(r);
        registerChronicle(r);
        registerEvents(r);
    }

    private static final List<String> BOARD_KINDS = List.of("daily", "weekly", "collect");

    /** Board kind of the {@code (0:daily|1:weekly|2:collect)} parse mark. */
    private static String boardKind(Ctx c) {
        return BOARD_KINDS.get(Math.clamp(c.mark(), 0, BOARD_KINDS.size() - 1));
    }

    private static final String KIND = "(0:daily|1:weekly|2:collect)";

    private static List<String> ids(List<QuestId> quests) {
        return quests.stream().map(QuestId::toString).toList();
    }

    // ------------------------------------------------------------------ state · progress

    private static void registerState(Registrar r) {
        r.text(
                Spec.of("harbor quest state"),
                (e, x) -> {
                    Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
                    Optional<QuestService> s = quests();
                    if (q.isEmpty() || s.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(e, x, p -> s.get().state(p.getUniqueId(), q.get()).key());
                },
                "[the] harbor quest state of %players% for [quest] %string%");
        r.number(
                Spec.of("harbor active quest count"),
                (e, x) ->
                        quests().map(
                                        s ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p -> s.active(p.getUniqueId()).size()))
                                .orElse(List.of()),
                "[the] (number|amount) of active harbor quests of %players%");
        ValueExpr.builder(
                        String.class,
                        Spec.of("active harbor quests"),
                        (e, x) -> {
                            Optional<QuestService> s = quests();
                            List<String> out = new ArrayList<>();
                            if (s.isPresent()) {
                                for (Player p : Args.players(e, x, 0)) {
                                    s.get()
                                            .active(p.getUniqueId())
                                            .forEach(v -> out.add(v.quest().toString()));
                                }
                            }
                            return out;
                        })
                .list()
                .register(r, "[all] [the] active harbor quests of %players%");
        r.condition(
                Spec.of("harbor quest completed"),
                (e, x) -> {
                    Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
                    Optional<QuestService> s = quests();
                    return q.isPresent()
                            && s.isPresent()
                            && Args.all(e, x, p -> s.get().isCompleted(p.getUniqueId(), q.get()));
                },
                "%players% (has|have) completed harbor quest %string%",
                "%players% (hasn't|has not|haven't|have not) completed harbor quest %string%");
        r.text(
                Spec.of("harbor quest name"),
                perView(QuestView::name),
                "[the] harbor quest name of %players% for [quest] %string%");
        r.text(
                Spec.of("harbor quest type"),
                perView(QuestView::type),
                "[the] harbor quest type of %players% for [quest] %string%");
        r.text(
                Spec.of("harbor quest cycle"),
                perView(QuestView::cycle),
                "[the] harbor quest cycle of %players% for [quest] %string%");
        ValueExpr.builder(
                        String.class,
                        Spec.of("harbor quest objectives"),
                        (e, x) -> {
                            Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
                            Optional<QuestService> s = quests();
                            List<String> out = new ArrayList<>();
                            if (q.isPresent() && s.isPresent()) {
                                for (Player p : Args.players(e, x, 0)) {
                                    view(s.get(), p, q.get())
                                            .ifPresent(
                                                    v ->
                                                            v.progress().keySet().stream()
                                                                    .sorted()
                                                                    .forEach(out::add));
                                }
                            }
                            return out;
                        })
                .list()
                .register(
                        r, "[all] [the] harbor quest objectives of %players% for [quest] %string%");
        r.number(
                Spec.of("harbor quest progress"),
                (e, x) -> {
                    Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
                    String objective = Args.string(e, x, 2).orElse("");
                    Optional<QuestService> s = quests();
                    if (q.isEmpty() || s.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p ->
                                    view(s.get(), p, q.get())
                                            .map(v -> (Number) v.progress().get(objective))
                                            .orElse(null));
                },
                "[the] harbor quest progress of %players% for [quest] %string% [objective]"
                        + " %string%");
        ValueExpr.builder(
                        String.class,
                        Spec.single("harbor quest objective types"),
                        (e, x) ->
                                Harbor.api()
                                        .service(ObjectiveTypeRegistry.class)
                                        .<List<String>>map(
                                                t -> t.types().stream().sorted().toList())
                                        .orElse(List.of()))
                .list()
                .register(r, "[all] [the] harbor quest objective types");

        // Server progress (async, last fetched value)
        r.number(
                Spec.single("harbor chapter clears"),
                (e, x) ->
                        Args.number(e, x, 0)
                                .flatMap(n -> CHAPTER_CLEARS.get(n.intValue()))
                                .map(c -> List.<Number>of(c))
                                .orElse(List.of()),
                "[the] harbor chapter %number% clear count");
        r.number(
                Spec.single("harbor climax donation rate"),
                climax(ServerProgressService.ClimaxStatus::donationRate),
                "[the] harbor climax donation rate");
        r.number(
                Spec.single("harbor climax required rate"),
                climax(ServerProgressService.ClimaxStatus::requiredRate),
                "[the] harbor climax required rate");
        r.number(
                Spec.single("harbor climax clears"),
                climax(ServerProgressService.ClimaxStatus::clears),
                "[the] harbor climax clears");
        r.number(
                Spec.single("harbor climax required clears"),
                climax(ServerProgressService.ClimaxStatus::requiredClears),
                "[the] harbor climax required clears");
        r.number(
                Spec.single("harbor climax active players"),
                climax(ServerProgressService.ClimaxStatus::activePlayers),
                "[the] harbor climax active players");
        r.condition(
                Spec.single("harbor climax ready"),
                (e, x) -> CLIMAX.get(true).map(c -> c.ready() || c.flagged()).orElse(false),
                "harbor climax is ready",
                "harbor climax (isn't|is not) ready");
    }

    // ------------------------------------------------------------------ actions · signals

    private static void registerActions(Registrar r) {
        r.effect(
                Spec.of("accept harbor quest"),
                (e, x) ->
                        forQuest(
                                e,
                                x,
                                (s, p, q) -> {
                                    var _ = s.accept(p, q);
                                }),
                "make %players% accept harbor quest %string%");
        r.effect(
                Spec.of("abandon harbor quest"),
                (e, x) ->
                        forQuest(
                                e,
                                x,
                                (s, p, q) -> {
                                    var _ = s.abandon(p, q);
                                }),
                "make %players% abandon harbor quest %string%");
        r.effect(
                Spec.of("turn in harbor quest"),
                (e, x) ->
                        forQuest(
                                e,
                                x,
                                (s, p, q) -> {
                                    var _ = s.turnIn(p, q);
                                }),
                "make %players% turn in harbor quest %string%");
        r.effect(
                Spec.of("track harbor quest"),
                (e, x) ->
                        forQuest(
                                e,
                                x,
                                (s, p, q) -> {
                                    var _ = s.track(p, q);
                                }),
                "make %players% track harbor quest %string%");
        r.effect(
                Spec.of("harbor quest signal"),
                (e, x) -> {
                    Optional<ObjectiveSignal> signal = Harbor.api().service(ObjectiveSignal.class);
                    String type = Args.string(e, x, 0).orElse("");
                    if (signal.isEmpty() || type.isEmpty()) {
                        return;
                    }
                    String target = Args.string(e, x, 1).orElse("");
                    int amount = Args.number(e, x, 2).map(Number::intValue).orElse(1);
                    for (Player p : Args.players(e, x, 3)) {
                        signal.get().signal(p, type, target, amount);
                    }
                },
                "harbor quest signal %string% [with target %-string%] [by %-number%] for %players%");
        r.effect(
                Spec.of("harbor quest set"),
                (e, x) -> {
                    Optional<ObjectiveSignal> signal = Harbor.api().service(ObjectiveSignal.class);
                    String type = Args.string(e, x, 0).orElse("");
                    Optional<Number> value = Args.number(e, x, 2);
                    if (signal.isEmpty() || type.isEmpty() || value.isEmpty()) {
                        return;
                    }
                    String target = Args.string(e, x, 1).orElse("");
                    for (Player p : Args.players(e, x, 3)) {
                        signal.get().set(p, type, target, value.get().intValue());
                    }
                },
                "harbor quest set %string% [with target %-string%] to %number% for %players%");
    }

    // ------------------------------------------------------------------ tracking · goals · admin

    private static void registerAdmin(Registrar r) {
        ValueExpr.builder(
                        String.class,
                        Spec.of("harbor tracked quest"),
                        (e, x) ->
                                quests().map(
                                                s ->
                                                        Args.perPlayer(
                                                                e,
                                                                x,
                                                                p ->
                                                                        s.tracked(p.getUniqueId())
                                                                                .map(
                                                                                        QuestId
                                                                                                ::toString)
                                                                                .orElse(null)))
                                        .orElse(List.of()))
                .changer(
                        String.class,
                        (e, x, mode, v) -> {
                            Optional<QuestService> s = quests();
                            if (s.isEmpty()) {
                                return;
                            }
                            Optional<QuestId> q =
                                    v == null ? Optional.empty() : quest(String.valueOf(v));
                            for (Player p : Args.players(e, x, 0)) {
                                if (mode == ChangeMode.SET) {
                                    q.ifPresent(id -> s.get().track(p, id));
                                } else {
                                    s.get().untrack(p);
                                }
                            }
                        },
                        ChangeMode.SET,
                        ChangeMode.DELETE,
                        ChangeMode.RESET)
                .register(r, "[the] harbor tracked quest of %players%");
        r.number(
                Spec.of("harbor quest goal"),
                (e, x) -> {
                    Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
                    String objective = Args.string(e, x, 2).orElse("");
                    Optional<QuestService> s = quests();
                    if (q.isEmpty() || s.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p ->
                                    view(s.get(), p, q.get())
                                            .map(v -> (Number) v.goals().get(objective))
                                            .orElse(null));
                },
                "[the] harbor quest (goal|target count) of %players% for [quest] %string%"
                        + " [objective] %string%");

        // Admin: same as /harbor quest complete · reset · set · progress
        Ext.await(
                r,
                "harbor admin complete quest",
                new int[] {2},
                c -> {
                    Optional<QuestService> s = quests();
                    Optional<QuestId> q = c.string(0).flatMap(QuestSyntax::quest);
                    Optional<Player> p = c.player(1);
                    if (s.isEmpty() || q.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().adminComplete(p.get(), q.get(), c.mark() != 1));
                },
                "harbor admin complete [the] harbor quest %string% for %player% [1:without"
                        + " reward[s]] [and (store|save) [the] result in %-objects%]");
        Ext.await(
                r,
                "harbor admin reset quest",
                new int[] {2},
                c -> {
                    Optional<QuestService> s = quests();
                    Optional<QuestId> q = c.string(0).flatMap(QuestSyntax::quest);
                    Optional<Player> p = c.player(1);
                    if (s.isEmpty() || q.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().adminReset(p.get(), q.get()));
                },
                "harbor admin reset [the] harbor quest %string% for %player% [and (store|save)"
                        + " [the] result in %-objects%]");
        Ext.await(
                r,
                "harbor admin set quest state",
                new int[] {3},
                c -> {
                    Optional<QuestService> s = quests();
                    Optional<Player> p = c.player(0);
                    Optional<QuestId> q = c.string(1).flatMap(QuestSyntax::quest);
                    Optional<QuestState> state =
                            c.string(2)
                                    .flatMap(
                                            raw ->
                                                    Keyed.fromKey(
                                                            QuestState.class,
                                                            raw.strip().toLowerCase(Locale.ROOT)));
                    if (s.isEmpty() || p.isEmpty() || q.isEmpty() || state.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().adminSetState(p.get(), q.get(), state.get()));
                },
                "harbor admin set [the] harbor quest state of %player% for [quest] %string% to"
                        + " %string% [and (store|save) [the] result in %-objects%]");
        r.effect(
                Spec.of("harbor admin set quest progress"),
                (e, x) -> {
                    Optional<QuestService> s = quests();
                    Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
                    String objective = Args.string(e, x, 2).orElse("");
                    Optional<Number> value = Args.number(e, x, 3);
                    if (s.isEmpty() || q.isEmpty() || objective.isEmpty() || value.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 0)) {
                        s.get().adminSetProgress(p, q.get(), objective, value.get().intValue());
                    }
                },
                "harbor admin set [the] harbor quest progress of %players% for [quest] %string%"
                        + " [objective] %string% to %number%");
    }

    // ------------------------------------------------------------------ request board

    private static void registerBoard(Registrar r) {
        Ext.expr(r, String.class, "harbor board requests")
                .list()
                .register(
                        c ->
                                quests().flatMap(
                                                s ->
                                                        c.player(0)
                                                                .flatMap(
                                                                        p ->
                                                                                s.board(
                                                                                        p
                                                                                                .getUniqueId(),
                                                                                        boardKind(
                                                                                                c))))
                                        .map(b -> ids(b.offers()))
                                        .orElse(List.of()),
                        "[all] [the] harbor " + KIND + " board (requests|offers) of %player%");
        Ext.expr(r, String.class, "harbor board chosen")
                .list()
                .register(
                        c ->
                                quests().flatMap(
                                                s ->
                                                        c.player(0)
                                                                .flatMap(
                                                                        p ->
                                                                                s.board(
                                                                                        p
                                                                                                .getUniqueId(),
                                                                                        boardKind(
                                                                                                c))))
                                        .map(b -> ids(b.chosen()))
                                        .orElse(List.of()),
                        "[all] [the] harbor "
                                + KIND
                                + " board (chosen|accepted) [requests] of"
                                + " %player%");
        Ext.expr(r, Number.class, "harbor board picks left")
                .single()
                .register(
                        c ->
                                quests().flatMap(
                                                s ->
                                                        c.player(0)
                                                                .flatMap(
                                                                        p ->
                                                                                s.board(
                                                                                        p
                                                                                                .getUniqueId(),
                                                                                        boardKind(
                                                                                                c))))
                                        .map(b -> List.<Number>of(b.remainingPicks()))
                                        .orElse(List.of()),
                        "[the] harbor " + KIND + " board picks (left|remaining) of %player%");
        Ext.await(
                r,
                "harbor draw board",
                new int[] {1},
                c -> {
                    Optional<QuestService> s = quests();
                    Optional<Player> p = c.player(0);
                    if (s.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.one(
                            s.get()
                                    .drawBoard(p.get(), boardKind(c))
                                    .thenApply(b -> b.map(v -> ids(v.offers())).orElse(List.of())));
                },
                "make %player% draw [the] harbor "
                        + KIND
                        + " board [and (store|save) [the]"
                        + " (requests|offers) in %-objects%]");
        Ext.await(
                r,
                "harbor admin reroll board",
                new int[] {1},
                c -> {
                    Optional<QuestService> s = quests();
                    Optional<Player> p = c.player(0);
                    if (s.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.one(
                            s.get()
                                    .adminRerollBoard(p.get(), boardKind(c))
                                    .thenApply(b -> b.map(v -> ids(v.offers())).orElse(List.of())));
                },
                "harbor admin reroll [the] harbor "
                        + KIND
                        + " board of %player% [and (store|save)"
                        + " [the] (requests|offers) in %-objects%]");
    }

    // ------------------------------------------------------------------ chronicle

    private static void registerChronicle(Registrar r) {
        ValueExpr.builder(
                        String.class,
                        Spec.of("harbor chronicle pieces"),
                        (e, x) -> {
                            Optional<ChronicleService> c = chronicle();
                            List<String> out = new ArrayList<>();
                            if (c.isPresent()) {
                                for (Player p : Args.players(e, x, 0)) {
                                    c.get().pieces(p.getUniqueId()).stream()
                                            .sorted()
                                            .forEach(out::add);
                                }
                            }
                            return out;
                        })
                .list()
                .register(r, "[all] [the] harbor chronicle pieces of %players%");
        r.number(
                Spec.of("harbor chronicle piece count"),
                (e, x) ->
                        chronicle()
                                .map(
                                        c ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p -> c.pieces(p.getUniqueId()).size()))
                                .orElse(List.of()),
                "[the] (number|amount) of harbor chronicle pieces of %players%");
        r.condition(
                Spec.of("harbor chronicle piece"),
                (e, x) -> {
                    Optional<ChronicleService> c = chronicle();
                    String piece = Args.string(e, x, 1).orElse("");
                    return c.isPresent()
                            && !piece.isEmpty()
                            && Args.all(e, x, p -> c.get().pieces(p.getUniqueId()).contains(piece));
                },
                "%players% (has|have) harbor chronicle piece %string%",
                "%players% (doesn't|does not|don't|do not) have harbor chronicle piece %string%");
        r.effect(
                Spec.of("open harbor chronicle"),
                (e, x) -> chronicle().ifPresent(c -> Args.players(e, x, 0).forEach(c::open)),
                "open harbor chronicle (to|for) %players%");
    }

    // ------------------------------------------------------------------ events

    private static void registerEvents(Registrar r) {
        // Shared: quest ID of the current Quest event
        r.text(
                Spec.single("harbor event quest"),
                (e, x) -> {
                    QuestId q =
                            switch (e) {
                                case QuestAcceptedEvent a -> a.quest();
                                case QuestCompletedEvent c -> c.quest();
                                case QuestAbandonedEvent a -> a.quest();
                                case QuestReadyEvent rd -> rd.quest();
                                case QuestProgressEvent pr -> pr.quest();
                                case QuestStateChangedEvent s -> s.quest();
                                default -> null;
                            };
                    return q == null ? List.of() : List.of(q.toString());
                },
                "[the] harbor event quest [id]");
        r.text(
                Spec.single("harbor event quest type"),
                (e, x) -> {
                    String t =
                            switch (e) {
                                case QuestAcceptedEvent a -> a.type();
                                case QuestCompletedEvent c -> c.type();
                                default -> null;
                            };
                    return Args.one(t);
                },
                "[the] harbor event quest type");

        r.event(
                "Harbor Quest Accept",
                QuestAcceptedEvent.class,
                "퀘스트를 받은 뒤 (event-player, event-string = 퀘스트 ID)",
                "harbor quest accept");
        r.value(QuestAcceptedEvent.class, Player.class, QuestAcceptedEvent::player);
        r.value(QuestAcceptedEvent.class, String.class, e -> e.quest().id().toString());

        r.event(
                "Harbor Quest Complete",
                QuestCompletedEvent.class,
                "퀘스트를 완료하고 보상을 받은 뒤 (event-offlineplayer, event-string = 퀘스트 ID)",
                "harbor quest complete");
        r.value(
                QuestCompletedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(QuestCompletedEvent.class, String.class, e -> e.quest().id().toString());
        r.text(
                Spec.in("harbor quest event cycle", QuestCompletedEvent.class),
                in(QuestCompletedEvent.class, QuestCompletedEvent::cycle),
                "[the] harbor completed quest cycle");
        r.number(
                Spec.in("harbor quest reward gold", QuestCompletedEvent.class),
                rewards(QuestRewardSummary::gold),
                "[the] harbor quest reward gold");
        r.number(
                Spec.in("harbor quest reward mulbineul", QuestCompletedEvent.class),
                rewards(QuestRewardSummary::mulbineul),
                "[the] harbor quest reward mulbineul");
        r.number(
                Spec.in("harbor quest reward exp", QuestCompletedEvent.class),
                rewards(QuestRewardSummary::exp),
                "[the] harbor quest reward (exp|experience)");
        r.number(
                Spec.in("harbor quest reward reputation", QuestCompletedEvent.class),
                rewards(QuestRewardSummary::reputation),
                "[the] harbor quest reward reputation");
        r.text(
                Spec.in("harbor quest reward title", QuestCompletedEvent.class),
                rewards(QuestRewardSummary::title),
                "[the] harbor quest reward title");
        r.text(
                Spec.in("harbor quest reward chronicle", QuestCompletedEvent.class),
                rewards(QuestRewardSummary::chronicle),
                "[the] harbor quest reward chronicle [piece]");
        ValueExpr.builder(
                        String.class,
                        Spec.in("harbor quest reward items", QuestCompletedEvent.class),
                        (e, x) ->
                                e instanceof QuestCompletedEvent c
                                        ? c.rewards().items()
                                        : List.of())
                .list()
                .register(r, "[all] [the] harbor quest reward items");

        r.event(
                "Harbor Quest Abandon",
                QuestAbandonedEvent.class,
                "퀘스트를 포기한 뒤 (event-player, event-string = 퀘스트 ID)",
                "harbor quest abandon");
        r.value(QuestAbandonedEvent.class, Player.class, QuestAbandonedEvent::player);
        r.value(QuestAbandonedEvent.class, String.class, e -> e.quest().toString());

        r.event(
                "Harbor Quest Ready",
                QuestReadyEvent.class,
                "목표를 모두 채워 완료 보고할 수 있게 된 뒤 (event-player, event-string = 퀘스트 ID)",
                "harbor quest ready");
        r.value(QuestReadyEvent.class, Player.class, QuestReadyEvent::player);
        r.value(QuestReadyEvent.class, String.class, e -> e.quest().toString());

        r.event(
                "Harbor Quest Progress",
                QuestProgressEvent.class,
                "목표 진행도가 바뀐 뒤 (event-player, event-string = 퀘스트 ID, event-number = 새 진행값)",
                "harbor quest progress");
        r.value(QuestProgressEvent.class, Player.class, QuestProgressEvent::player);
        r.value(QuestProgressEvent.class, String.class, e -> e.quest().toString());
        r.value(QuestProgressEvent.class, Number.class, QuestProgressEvent::value);
        r.text(
                Spec.in("harbor progress objective", QuestProgressEvent.class),
                in(QuestProgressEvent.class, QuestProgressEvent::objective),
                "[the] harbor progress objective");
        r.number(
                Spec.in("harbor progress value", QuestProgressEvent.class),
                in(QuestProgressEvent.class, QuestProgressEvent::value),
                "[the] harbor progress value");
        r.number(
                Spec.in("harbor progress goal", QuestProgressEvent.class),
                in(QuestProgressEvent.class, QuestProgressEvent::count),
                "[the] harbor progress (goal|target count)");

        r.event(
                "Harbor Quest State Change",
                QuestStateChangedEvent.class,
                "퀘스트 상태가 바뀐 뒤 (event-offlineplayer, event-string = 퀘스트 ID)",
                "harbor quest state change");
        r.value(
                QuestStateChangedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(QuestStateChangedEvent.class, String.class, e -> e.quest().toString());
        r.text(
                Spec.in("harbor quest old state", QuestStateChangedEvent.class),
                in(QuestStateChangedEvent.class, s -> s.from().key()),
                "[the] old harbor quest state");
        r.text(
                Spec.in("harbor quest new state", QuestStateChangedEvent.class),
                in(QuestStateChangedEvent.class, s -> s.to().key()),
                "[the] new harbor quest state");

        r.event(
                "Harbor Chapter Complete",
                ChapterCompletedEvent.class,
                "메인 이야기 장을 깬 뒤, 연대기 회상 포함 (event-offlineplayer, event-number = 장 번호)",
                "harbor chapter complete");
        r.value(
                ChapterCompletedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(ChapterCompletedEvent.class, Number.class, ChapterCompletedEvent::chapter);
        r.number(
                Spec.in("harbor completed chapter", ChapterCompletedEvent.class),
                in(ChapterCompletedEvent.class, ChapterCompletedEvent::chapter),
                "[the] harbor completed chapter");
        r.condition(
                Spec.in("harbor chapter recap", ChapterCompletedEvent.class),
                (e, x) -> e instanceof ChapterCompletedEvent c && c.recap(),
                "[the] harbor chapter is a recap",
                "[the] harbor chapter (isn't|is not) a recap");

        r.event(
                "Harbor Chronicle Piece",
                ChroniclePieceEvent.class,
                "진실의 조각을 얻은 뒤 (event-offlineplayer, event-string = 조각 ID, event-number = 모은 수)",
                "harbor chronicle piece");
        r.value(
                ChroniclePieceEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(ChroniclePieceEvent.class, String.class, ChroniclePieceEvent::piece);
        r.value(ChroniclePieceEvent.class, Number.class, ChroniclePieceEvent::owned);
        r.text(
                Spec.in("harbor chronicle event piece", ChroniclePieceEvent.class),
                in(ChroniclePieceEvent.class, ChroniclePieceEvent::piece),
                "[the] harbor obtained chronicle piece");
        r.number(
                Spec.in("harbor chronicle owned", ChroniclePieceEvent.class),
                in(ChroniclePieceEvent.class, ChroniclePieceEvent::owned),
                "[the] harbor chronicle owned count");
        r.number(
                Spec.in("harbor chronicle total", ChroniclePieceEvent.class),
                in(ChroniclePieceEvent.class, ChroniclePieceEvent::total),
                "[the] harbor chronicle total count");
    }

    @FunctionalInterface
    private interface QuestCall {
        void call(QuestService service, Player player, QuestId quest);
    }

    private static void forQuest(
            org.bukkit.event.Event e, ch.njol.skript.lang.Expression<?>[] x, QuestCall call) {
        Optional<QuestId> q = Args.string(e, x, 1).flatMap(QuestSyntax::quest);
        Optional<QuestService> s = quests();
        if (q.isEmpty() || s.isEmpty()) {
            return;
        }
        for (Player p : Args.players(e, x, 0)) {
            call.call(s.get(), p, q.get());
        }
    }
}
