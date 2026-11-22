package wiki.creeper.harbor.skript.bridge;

import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Fishing is enabled: the fish caught event and the expressions used inside it (species
 * · grade · region · size · quality). event-player is Skript's default value (PlayerEvent).
 */
public final class FishingSyntax {

    private FishingSyntax() {}

    public static void register(Registrar r) {
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
                (e, x) ->
                        e instanceof FishCaughtEvent c
                                ? Args.one(c.fish().id().id().toString())
                                : java.util.List.of(),
                "[the] harbor caught fish [id]");
        r.text(
                Spec.in("harbor caught fish name", FishCaughtEvent.class),
                (e, x) ->
                        e instanceof FishCaughtEvent c
                                ? Args.one(c.fish().name())
                                : java.util.List.of(),
                "[the] harbor caught fish name");
        r.text(
                Spec.in("harbor catch grade", FishCaughtEvent.class),
                (e, x) ->
                        e instanceof FishCaughtEvent c
                                ? Args.one(c.fish().grade().key())
                                : java.util.List.of(),
                "[the] harbor catch grade");
        r.text(
                Spec.in("harbor catch region", FishCaughtEvent.class),
                (e, x) ->
                        e instanceof FishCaughtEvent c
                                ? Args.one(c.region().id().toString())
                                : java.util.List.of(),
                "[the] harbor catch region");
        r.number(
                Spec.in("harbor catch size", FishCaughtEvent.class),
                (e, x) ->
                        e instanceof FishCaughtEvent c
                                ? Args.one(c.item().sizeCm())
                                : java.util.List.of(),
                "[the] harbor catch size");
        r.number(
                Spec.in("harbor catch quality", FishCaughtEvent.class),
                (e, x) ->
                        e instanceof FishCaughtEvent c
                                ? Args.one(c.item().quality().stars())
                                : java.util.List.of(),
                "[the] harbor catch quality");
        r.condition(
                Spec.in("harbor first catch", FishCaughtEvent.class),
                (e, x) -> e instanceof FishCaughtEvent c && c.firstCatch(),
                "[the] harbor catch is [a] first catch",
                "[the] harbor catch (isn't|is not) [a] first catch");
    }
}
