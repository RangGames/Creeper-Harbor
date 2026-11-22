package wiki.creeper.harbor.skript.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.api.FoodBuff;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;
import wiki.creeper.harbor.skript.lambda.ValueExpr;

/**
 * When Harbor-Fishing is enabled: food buffs (fishing/food.yml, one at a time) through {@link
 * FishingService}: the active buff and its expiry, applying a food's buff as if eaten, clearing it.
 * Main thread only (player data). Registered from {@link FishingSyntax#register}.
 */
final class FoodSyntax {

    private FoodSyntax() {}

    private static Optional<FishingService> fishing() {
        return Harbor.api().service(FishingService.class);
    }

    private static <T> Spec.Getter<T> perBuff(Function<FoodBuff, @Nullable T> value) {
        return (e, x) ->
                fishing()
                        .map(
                                f ->
                                        Args.perPlayer(
                                                e,
                                                x,
                                                p ->
                                                        f.foodBuff(p.getUniqueId())
                                                                .map(value)
                                                                .orElse(null)))
                        .orElse(List.of());
    }

    static void register(Registrar r) {
        r.text(
                Spec.of("harbor food buff"),
                perBuff(b -> b.item().toString()),
                "[the] [active] harbor food buff of %players%");
        ValueExpr.builder(
                        ch.njol.skript.util.Date.class,
                        Spec.of("harbor food buff expiry"),
                        perBuff(b -> new ch.njol.skript.util.Date(b.expiresAt().toEpochMilli())))
                .register(r, "[the] harbor food buff (expiry|expiration|end) [time] of %players%");
        r.number(
                Spec.of("harbor food buff seconds"),
                perBuff(
                        b ->
                                Math.max(
                                        0,
                                        Duration.between(Instant.now(), b.expiresAt())
                                                .toSeconds())),
                "[the] harbor food buff (remaining|left) seconds of %players%");
        r.condition(
                Spec.of("harbor has food buff"),
                (e, x) -> {
                    Optional<FishingService> f = fishing();
                    return f.isPresent()
                            && Args.all(e, x, p -> f.get().foodBuff(p.getUniqueId()).isPresent());
                },
                "%players% (has|have) [a] harbor food buff",
                "%players% (doesn't|does not|don't|do not) have [a] harbor food buff");
        r.effect(
                Spec.of("harbor admin apply food buff"),
                (e, x) -> {
                    Optional<FishingService> f = fishing();
                    Optional<ItemId> food =
                            Args.string(e, x, 0)
                                    .flatMap(s -> Ids.parse(s, ItemId.PREFIX))
                                    .map(ItemId::new);
                    if (f.isEmpty() || food.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        f.get().applyFood(p, food.get());
                    }
                },
                "harbor admin apply [the] [harbor] food buff [of] %string% to %players%");
        r.effect(
                Spec.of("harbor admin clear food buff"),
                (e, x) -> fishing().ifPresent(f -> Args.players(e, x, 0).forEach(f::clearFood)),
                "harbor admin clear [the] [harbor] food buff of %players%");
    }
}
