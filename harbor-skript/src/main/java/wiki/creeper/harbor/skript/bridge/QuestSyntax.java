package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.quest.api.ObjectiveSignal;
import wiki.creeper.harbor.quest.api.QuestService;
import wiki.creeper.harbor.quest.api.event.QuestAcceptedEvent;
import wiki.creeper.harbor.quest.api.event.QuestCompletedEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Quest is enabled: quest state, completion and number in progress, accept, abandon and
 * track, objective signals (scripts advance objectives like another module's type), accept and
 * complete events.
 *
 * <p>The type of an objective signal must be an objective type used by quest content (a built-in
 * type or one registered by another module). Scripts do not register new types: the content check
 * runs before Skript, so an unknown type fails the check.
 */
public final class QuestSyntax {

    private QuestSyntax() {}

    private static Optional<QuestService> quests() {
        return Harbor.api().service(QuestService.class);
    }

    private static Optional<QuestId> quest(String raw) {
        return Ids.parse(raw, QuestId.PREFIX).map(QuestId::new);
    }

    public static void register(Registrar r) {
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
