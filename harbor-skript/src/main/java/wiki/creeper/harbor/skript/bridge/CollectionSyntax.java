package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.api.CodexService;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.api.event.CodexRegisteredEvent;
import wiki.creeper.harbor.collection.api.event.RecordBrokenEvent;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Collection is enabled: codex completion rate and registration, personal biggest fish,
 * opening the codex, codex registration and new record events.
 */
public final class CollectionSyntax {

    private CollectionSyntax() {}

    private static Optional<CodexService> codex() {
        return Harbor.api().service(CodexService.class);
    }

    private static Optional<FishId> fish(String raw) {
        return Ids.parse(raw, FishId.PREFIX).map(FishId::new);
    }

    public static void register(Registrar r) {
        r.number(
                Spec.of("harbor codex percent"),
                (e, x) ->
                        codex().map(
                                        c ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                c.percent(
                                                                        p.getUniqueId(),
                                                                        CodexScope.SEASON,
                                                                        null)))
                                .orElse(List.of()),
                "[the] harbor [season] codex percent[age] of %players%");
        r.number(
                Spec.of("harbor total codex percent"),
                (e, x) ->
                        codex().map(
                                        c ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                c.percent(
                                                                        p.getUniqueId(),
                                                                        CodexScope.TOTAL,
                                                                        null)))
                                .orElse(List.of()),
                "[the] harbor total codex percent[age] of %players%");
        r.condition(
                Spec.of("harbor codex registered"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                    Optional<CodexService> c = codex();
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
                                                            CodexScope.TOTAL));
                },
                "%players% (has|have) registered harbor fish %string%",
                "%players% (hasn't|has not|haven't|have not) registered harbor fish %string%");
        r.number(
                Spec.of("harbor best size"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 1).flatMap(CollectionSyntax::fish);
                    Optional<RecordService> records = Harbor.api().service(RecordService.class);
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
                Spec.of("open harbor codex"),
                (e, x) -> {
                    Optional<CodexService> c = codex();
                    if (c.isEmpty()) {
                        return;
                    }
                    Object owner = Args.single(e, x, 0);
                    for (Player viewer : Args.players(e, x, 1)) {
                        c.get()
                                .open(
                                        viewer,
                                        owner instanceof OfflinePlayer o
                                                ? o.getUniqueId()
                                                : viewer.getUniqueId(),
                                        0);
                    }
                },
                "open harbor codex [of %-offlineplayer%] (to|for) %players%");

        r.event(
                "Harbor Codex Register",
                CodexRegisteredEvent.class,
                "도감에 새 어종이 등록된 뒤 (event-offlineplayer, event-string = 어종 ID)",
                "harbor codex regist(er|ration)");
        r.value(
                CodexRegisteredEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(CodexRegisteredEvent.class, String.class, e -> e.fish().id().toString());

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
    }
}
