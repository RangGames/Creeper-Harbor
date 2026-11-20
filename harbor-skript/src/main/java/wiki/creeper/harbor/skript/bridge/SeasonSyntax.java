package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/** Harbor-Season이 켜져 있을 때: 시즌 주차·이름, 칭호 보유·장착. */
public final class SeasonSyntax {

    private SeasonSyntax() {}

    private static Optional<SeasonClock> clock() {
        return wiki.creeper.harbor.core.api.Harbor.api().service(SeasonClock.class);
    }

    private static Optional<TitleService> titles() {
        return wiki.creeper.harbor.core.api.Harbor.api().service(TitleService.class);
    }

    public static void register(Registrar r) {
        r.number(
                Spec.single("harbor season week"),
                (e, x) -> clock().map(c -> List.<Number>of(c.week())).orElse(List.of()),
                "[the] harbor season week");
        r.text(
                Spec.single("harbor season name"),
                (e, x) -> clock().map(c -> List.of(c.displayName())).orElse(List.of()),
                "[the] harbor season name");
        r.condition(
                Spec.of("harbor title owned"),
                (e, x) -> {
                    Optional<TitleId> title = Args.string(e, x, 1).flatMap(TitleId::tryOf);
                    Optional<TitleService> t = titles();
                    return title.isPresent()
                            && t.isPresent()
                            && Args.all(e, x, p -> t.get().has(p.getUniqueId(), title.get()));
                },
                "%players% (has|have) harbor title %string%",
                "%players% (doesn't|does not|don't|do not) have harbor title %string%");
        r.text(
                Spec.of("equipped harbor title"),
                (e, x) ->
                        titles().map(
                                        t ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                t.equipped(p.getUniqueId())
                                                                        .map(TitleId::toString)
                                                                        .orElse(null)))
                                .orElse(List.of()),
                "[the] equipped harbor title of %players%");
        r.effect(
                Spec.of("equip harbor title"),
                (e, x) -> {
                    Optional<TitleId> title = Args.string(e, x, 0).flatMap(TitleId::tryOf);
                    Optional<TitleService> t = titles();
                    if (title.isEmpty() || t.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        var _ = t.get().equip(p.getUniqueId(), title.get());
                    }
                },
                "equip harbor title %string% (for|to|on) %players%");
        r.effect(
                Spec.of("unequip harbor title"),
                (e, x) ->
                        titles().ifPresent(
                                        t -> {
                                            for (Player p : Args.players(e, x, 0)) {
                                                var _ = t.equip(p.getUniqueId(), null);
                                            }
                                        }),
                "unequip harbor title (of|for|from) %players%");
    }
}
