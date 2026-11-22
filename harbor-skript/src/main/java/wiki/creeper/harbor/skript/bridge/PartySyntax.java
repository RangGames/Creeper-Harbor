package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.PartyService;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Party changes (Core PartyService, implemented by Harbor-Boss; always registered): create, invite,
 * accept, leave, kick, disband, promote and set role, with the same rules and notices as the /party
 * commands. The result is stored as text ({@code ok}, {@code not_leader}, …; {@code unsupported}
 * without Harbor-Boss). Party lookups are in {@link CoreServerSyntax}.
 */
public final class PartySyntax {

    private PartySyntax() {}

    private static String key(PartyService.Result r) {
        return r.name().toLowerCase(Locale.ROOT);
    }

    /** Runs a one-player change and stores the result in the argument at index out. */
    private static void one(Ctx c, int out, BiFunction<PartyService, UUID, PartyService.Result> f) {
        Optional<Player> p = c.player(0);
        if (p.isEmpty()) {
            c.set(out, List.of());
            return;
        }
        PartyService.Result r =
                api().parties()
                        .map(s -> f.apply(s, p.get().getUniqueId()))
                        .orElse(PartyService.Result.UNSUPPORTED);
        c.set(out, List.of(key(r)));
    }

    /** Runs a change from player 0 on player 1 and stores the result at index out. */
    private static void two(
            Ctx c, int out, Function<PartyService, BiFunction<UUID, UUID, PartyService.Result>> f) {
        Optional<Player> actor = c.player(0);
        Optional<OfflinePlayer> target = c.offlinePlayer(1);
        if (actor.isEmpty() || target.isEmpty()) {
            c.set(out, List.of());
            return;
        }
        PartyService.Result r =
                api().parties()
                        .map(
                                s ->
                                        f.apply(s)
                                                .apply(
                                                        actor.get().getUniqueId(),
                                                        target.get().getUniqueId()))
                        .orElse(PartyService.Result.UNSUPPORTED);
        c.set(out, List.of(key(r)));
    }

    private static final String RESULT = " [and (store|save) [the] result in %-objects%]";

    public static void register(Registrar r) {
        Ext.effect(
                r,
                "harbor party create",
                c -> one(c, 1, PartyService::create),
                "make %player% create [a] harbor party" + RESULT);
        Ext.effect(
                r,
                "harbor party accept",
                c -> one(c, 1, PartyService::accept),
                "make %player% accept [the] harbor party invite[ation]" + RESULT);
        Ext.effect(
                r,
                "harbor party leave",
                c -> one(c, 1, PartyService::leave),
                "make %player% leave [the|their|his|her] harbor party" + RESULT);
        Ext.effect(
                r,
                "harbor party disband",
                c -> one(c, 1, PartyService::disband),
                "make %player% disband [the|their|his|her] harbor party" + RESULT);
        Ext.effect(
                r,
                "harbor party invite",
                c -> two(c, 2, s -> s::invite),
                "make %player% invite %offlineplayer% to [the|their|his|her] harbor party"
                        + RESULT);
        Ext.effect(
                r,
                "harbor party kick",
                c -> two(c, 2, s -> s::kick),
                "make %player% kick %offlineplayer% from [the|their|his|her] harbor party"
                        + RESULT);
        Ext.effect(
                r,
                "harbor party promote",
                c -> two(c, 2, s -> s::promote),
                "make %player% promote %offlineplayer% to harbor party leader" + RESULT);
        Ext.effect(
                r,
                "harbor party set role",
                c -> {
                    Optional<PartyService.Role> role = c.string(2).flatMap(PartySyntax::role);
                    if (role.isEmpty()) {
                        c.set(3, List.of(key(PartyService.Result.INVALID_ROLE)));
                        return;
                    }
                    two(c, 3, s -> (a, t) -> s.setRole(a, t, role.get()));
                },
                "make %player% set [the] harbor party role of %offlineplayer% to %string%"
                        + RESULT);

        Ext.expr(r, OfflinePlayer.class, "harbor party inviter")
                .register(
                        c ->
                                api().parties()
                                        .map(
                                                s ->
                                                        c.players(0).stream()
                                                                .flatMap(
                                                                        p ->
                                                                                s
                                                                                        .pendingInvite(
                                                                                                p
                                                                                                        .getUniqueId())
                                                                                        .stream())
                                                                .map(Shared::offline)
                                                                .toList())
                                        .orElse(List.of()),
                        "[the] harbor party inviter of %players%");
    }

    /** Role key (leader · captain · harpooner · reeler · mechanic · quartermaster · member). */
    private static Optional<PartyService.Role> role(String raw) {
        String key = raw.strip().toUpperCase(Locale.ROOT);
        for (PartyService.Role role : PartyService.Role.values()) {
            if (role.name().equals(key)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
