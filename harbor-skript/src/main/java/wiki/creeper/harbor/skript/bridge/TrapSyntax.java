package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.fishing.api.TrapAction;
import wiki.creeper.harbor.fishing.api.TrapService;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Fishing is enabled: trap actions through {@link TrapService} (place · harvest · add
 * bait · remove, admin place and remove) and the trap at a location. Actions run the same flow as
 * the trap screen (DB transaction first, items after the commit, mail during a server move) and are
 * awaiting effects: the outcome key (ok · not_owner · inventory_full …) and the amount (the trap ID
 * for a placement) can be stored in variables. Registered from {@link FishingSyntax#register}.
 */
final class TrapSyntax {

    private TrapSyntax() {}

    /** Optional result variables: outcome key, then amount / trap ID. */
    private static final String STORE =
            " [and (store|save) [the] result in %-objects%] [and (store|save) [the]"
                    + " (amount|count|[trap] id) in %-objects%]";

    /** Action of a player-and-trap effect. */
    @FunctionalInterface
    private interface Call {
        CompletableFuture<TrapAction> run(TrapService traps, Player owner, long trapId);
    }

    private static Optional<TrapService> traps() {
        return Harbor.api().service(TrapService.class);
    }

    private static Optional<ItemId> item(String raw) {
        return Ids.parse(raw, ItemId.PREFIX).map(ItemId::new);
    }

    /** Outcome key and amount as two results; refreshes the cached trap values. */
    private static CompletableFuture<List<?>[]> result(
            UUID owner, CompletableFuture<TrapAction> action, boolean idAsAmount) {
        return action.thenApply(
                a -> {
                    FishingSyntax.refreshTraps(owner, a.trapId());
                    long amount = idAsAmount ? a.trapId() : a.amount();
                    return new List<?>[] {
                        List.of(a.outcome().name().toLowerCase(Locale.ROOT)),
                        a.ok() ? List.of(amount) : List.of()
                    };
                });
    }

    /** Player (argument 0) and trap ID (argument 1). */
    private static @Nullable CompletableFuture<List<?>[]> onTrap(Ctx c, Call call) {
        Optional<TrapService> t = traps();
        Optional<Player> p = c.player(0);
        Optional<Number> id = c.number(1);
        if (t.isEmpty() || p.isEmpty() || id.isEmpty()) {
            return null;
        }
        return result(
                p.get().getUniqueId(), call.run(t.get(), p.get(), id.get().longValue()), false);
    }

    /** Placement with the argument indices of the trap item, location and player. */
    private static @Nullable CompletableFuture<List<?>[]> place(
            Ctx c, boolean takeItem, int itemArg, int locationArg, int playerArg) {
        Optional<TrapService> t = traps();
        Optional<ItemId> trap = c.string(itemArg).flatMap(TrapSyntax::item);
        Optional<Location> at = c.as(locationArg, Location.class);
        Optional<Player> p = c.player(playerArg);
        if (t.isEmpty() || trap.isEmpty() || at.isEmpty() || p.isEmpty()) {
            return null;
        }
        return result(
                p.get().getUniqueId(),
                t.get().place(p.get(), at.get(), trap.get(), takeItem),
                true);
    }

    static void register(Registrar r) {
        r.number(
                Spec.single("harbor trap at"),
                (e, x) -> {
                    Optional<TrapService> t = traps();
                    if (!(Args.single(e, x, 0) instanceof Location loc) || t.isEmpty()) {
                        return List.of();
                    }
                    OptionalLong id = t.get().trapIdAt(loc);
                    return id.isPresent() ? List.<Number>of(id.getAsLong()) : List.of();
                },
                "[the] harbor trap [id] at %location%");

        Ext.await(
                r,
                "harbor harvest trap",
                new int[] {2, 3},
                c -> onTrap(c, TrapService::harvest),
                "make %player% harvest harbor trap %number%" + STORE);
        Ext.await(
                r,
                "harbor add trap bait",
                new int[] {2, 3},
                c -> onTrap(c, TrapService::addBait),
                "make %player% add bait to harbor trap %number%" + STORE);
        Ext.await(
                r,
                "harbor remove trap",
                new int[] {2, 3},
                c -> onTrap(c, TrapService::remove),
                "make %player% (remove|retrieve|pick up) harbor trap %number%" + STORE);
        Ext.await(
                r,
                "harbor place trap",
                new int[] {3, 4},
                c -> place(c, true, 1, 2, 0),
                "make %player% place [a] harbor trap %string% at %location%" + STORE);
        Ext.await(
                r,
                "harbor admin place trap",
                new int[] {3, 4},
                c -> place(c, false, 0, 1, 2),
                "harbor admin place [a] harbor trap %string% at %location% for %player%" + STORE);
        Ext.await(
                r,
                "harbor admin remove trap",
                new int[] {1},
                c -> {
                    Optional<TrapService> t = traps();
                    Optional<Number> id = c.number(0);
                    if (t.isEmpty() || id.isEmpty()) {
                        return null;
                    }
                    long trapId = id.get().longValue();
                    return Ext.value(
                            t.get()
                                    .adminRemove(trapId)
                                    .thenApply(
                                            ok -> {
                                                FishingSyntax.refreshTrap(trapId);
                                                return ok;
                                            }));
                },
                "harbor admin remove harbor trap %number% [and (store|save) [the] result in"
                        + " %-objects%]");
    }
}
