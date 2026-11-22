package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.api.ExternalEvents;
import wiki.creeper.harbor.core.api.PartyService;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.ScriptArgs;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Core server syntax (always registered): server moves with results, parties, the per-player world
 * border (script priority = World environment slot), content reload and validation, external
 * events, and display rigs.
 */
public final class CoreServerSyntax {

    private CoreServerSyntax() {}

    public static void register(Registrar r) {
        moves(r);
        parties(r);
        borders(r);
        content(r);
        rigs(r);
    }

    // ---------------------------------------------------------------- Server moves

    private static String moveResult(MoveResult result) {
        return result.ok() ? "ok" : result.reason();
    }

    private static void moves(Registrar r) {
        Ext.await(
                r,
                "harbor send to server",
                new int[] {2},
                c -> {
                    Optional<String> server = c.string(1).map(String::strip);
                    if (server.isEmpty() || server.get().isEmpty()) {
                        return null;
                    }
                    List<CompletableFuture<String>> results = new ArrayList<>();
                    for (Player p : c.players(0)) {
                        results.add(
                                api().proxy()
                                        .requestMove(p.getUniqueId(), server.get(), "skript")
                                        .thenApply(CoreServerSyntax::moveResult));
                    }
                    return Ext.one(Shared.all(results));
                },
                "harbor send %players% to server %string% [and (store|save) [the] result[s] in"
                        + " %-objects%]");
        Ext.await(
                r,
                "harbor send to lobby with result",
                new int[] {1},
                c -> {
                    List<CompletableFuture<String>> results = new ArrayList<>();
                    for (Player p : c.players(0)) {
                        results.add(
                                api().proxy()
                                        .requestLobby(p.getUniqueId(), "skript")
                                        .thenApply(CoreServerSyntax::moveResult));
                    }
                    return Ext.one(Shared.all(results));
                },
                "harbor send %players% to [the] lobby and (store|save) [the] result[s] in"
                        + " %objects%");
        Ext.await(
                r,
                "harbor send to instance",
                new int[] {2},
                c -> {
                    Optional<String> server = c.string(1).map(String::strip);
                    List<UUID> party = c.players(0).stream().map(Player::getUniqueId).toList();
                    if (server.isEmpty() || server.get().isEmpty() || party.isEmpty()) {
                        return null;
                    }
                    return Ext.value(
                            api().proxy()
                                    .requestInstance(party, server.get(), "")
                                    .thenApply(CoreServerSyntax::moveResult));
                },
                "harbor send %players% to instance [server] %string% [and (store|save) [the]"
                        + " result in %-objects%]");
    }

    // ---------------------------------------------------------------- Parties

    private static Optional<PartyService> parties() {
        return api().parties();
    }

    private static void parties(Registrar r) {
        Ext.expr(r, OfflinePlayer.class, "harbor party members")
                .list()
                .register(
                        c ->
                                c
                                        .player(0)
                                        .map(
                                                p ->
                                                        parties()
                                                                .map(
                                                                        s ->
                                                                                s.membersOf(
                                                                                        p
                                                                                                .getUniqueId()))
                                                                .orElse(List.of(p.getUniqueId())))
                                        .orElse(List.of())
                                        .stream()
                                        .map(Shared::offline)
                                        .toList(),
                        "[the] harbor party members of %player%");
        Ext.expr(r, OfflinePlayer.class, "harbor party leader")
                .register(
                        c ->
                                c.players(0).stream()
                                        .flatMap(p -> partyOf(p).stream())
                                        .map(party -> Shared.offline(party.leader()))
                                        .toList(),
                        "[the] harbor party leader of %players%");
        Ext.expr(r, String.class, "harbor party role")
                .register(
                        c ->
                                c.players(0).stream()
                                        .flatMap(
                                                p ->
                                                        partyOf(p).stream()
                                                                .flatMap(
                                                                        party ->
                                                                                party
                                                                                        .members()
                                                                                        .stream())
                                                                .filter(
                                                                        m ->
                                                                                m.player()
                                                                                        .equals(
                                                                                                p
                                                                                                        .getUniqueId())))
                                        .map(m -> m.role().name().toLowerCase(Locale.ROOT))
                                        .toList(),
                        "[the] harbor party role of %players%");
        Ext.expr(r, Number.class, "harbor party size")
                .register(
                        c ->
                                c.players(0).stream()
                                        .map(
                                                p ->
                                                        (Number)
                                                                partyOf(p)
                                                                        .map(
                                                                                x ->
                                                                                        x.members()
                                                                                                .size())
                                                                        .orElse(1))
                                        .toList(),
                        "[the] harbor party size of %players%");
        Ext.condition(
                r,
                "harbor in party",
                c -> {
                    List<Player> players = c.players(0);
                    return !players.isEmpty()
                            && players.stream().allMatch(p -> partyOf(p).isPresent());
                },
                "%players% (is|are) in a harbor party",
                "%players% (isn't|is not|aren't|are not) in a harbor party");
        Ext.condition(
                r,
                "harbor same party",
                c -> {
                    Optional<Player> a = c.player(0);
                    Optional<Player> b = c.player(1);
                    if (a.isEmpty() || b.isEmpty()) {
                        return false;
                    }
                    UUID x = a.get().getUniqueId();
                    UUID y = b.get().getUniqueId();
                    return parties().map(s -> s.sameParty(x, y)).orElse(x.equals(y));
                },
                "%player% (is|are) in [the] same harbor party as %player%",
                "%player% (isn't|is not|aren't|are not) in [the] same harbor party as %player%");
    }

    private static Optional<PartyService.Party> partyOf(Player p) {
        return parties().flatMap(s -> s.partyOf(p.getUniqueId()));
    }

    // ---------------------------------------------------------------- World border

    private static void borders(Registrar r) {
        Ext.effect(
                r,
                "harbor show world border",
                c -> {
                    Optional<Location> center = c.as(0, Location.class);
                    double size = c.number(1).map(Number::doubleValue).orElse(0.0);
                    if (center.isEmpty() || !(size > 0)) {
                        return;
                    }
                    int warning = Math.max(0, c.number(2).map(Number::intValue).orElse(0));
                    var border = new WorldBorderService.Border(center.get(), size, warning);
                    for (Player p : c.players(3)) {
                        api().worldBorders()
                                .request(p, WorldBorderService.Priority.WORLD_ENVIRONMENT, border);
                    }
                },
                "harbor show world border at %location% with size %number% [with warning"
                        + " distance %-number%] (to|for) %players%");
        Ext.effect(
                r,
                "harbor show warning border",
                c -> {
                    for (Player p : c.players(0)) {
                        Location at = p.getLocation();
                        if (at != null) {
                            api().worldBorders()
                                    .request(
                                            p,
                                            WorldBorderService.Priority.WORLD_ENVIRONMENT,
                                            WorldBorderService.Border.warning(at));
                        }
                    }
                },
                "harbor show warning border (to|for) %players%");
        Ext.effect(
                r,
                "harbor clear world border",
                c -> {
                    for (Player p : c.players(0)) {
                        api().worldBorders()
                                .clear(p, WorldBorderService.Priority.WORLD_ENVIRONMENT);
                    }
                },
                "harbor clear world border (of|for) %players%");
    }

    // ---------------------------------------------------------------- Content · external events

    private static void content(Registrar r) {
        // pattern 0: reload (fire-and-forget allowed), pattern 1: validate only
        Ext.await(
                r,
                "harbor reload content",
                new int[] {1, 2},
                c -> {
                    Set<String> types = new HashSet<>(c.strings(0));
                    boolean apply = c.pattern() == 0;
                    return api().content()
                            .reload(types, apply)
                            .thenApply(
                                    res ->
                                            switch (res) {
                                                case Result.Ok<?, ValidationErrors> ok ->
                                                        new List<?>[] {List.of(true), List.of()};
                                                case Result.Err<?, ValidationErrors>(
                                                                ValidationErrors errors) ->
                                                        new List<?>[] {
                                                            List.of(false),
                                                            errors.list().stream()
                                                                    .map(ValidationError::toString)
                                                                    .toList()
                                                        };
                                            });
                },
                "harbor reload content [types %-strings%] [and (store|save) [the] result in"
                        + " %-objects% [with errors in %-objects%]]",
                "harbor validate content [types %-strings%] and (store|save) [the] result in"
                        + " %objects% [with errors in %-objects%]");

        r.event(
                "Harbor Content Reload",
                ContentReloadedEvent.class,
                "콘텐츠 리로드가 적용된 뒤 (harbor reloaded content types = 이번에 다시 읽은 형식 이름)",
                "harbor content reload");
        Ext.expr(r, String.class, "harbor reloaded content types")
                .in(ContentReloadedEvent.class)
                .list()
                .register(
                        c ->
                                c.event() instanceof ContentReloadedEvent e
                                        ? List.copyOf(e.reloadedTypes())
                                        : List.of(),
                        "[the] harbor reloaded content types");

        Ext.effect(
                r,
                "harbor emit external event",
                c -> {
                    String type = c.string(0).map(String::strip).orElse("");
                    if (!type.matches(ExternalEvents.TYPE_PATTERN)) {
                        return;
                    }
                    Optional<OfflinePlayer> who = c.offlinePlayer(1);
                    Map<String, Object> data = new HashMap<>();
                    ScriptArgs.pairs(c.strings(2))
                            .forEach((k, v) -> data.put(k, ScriptArgs.typed(v)));
                    api().events()
                            .emit(
                                    type,
                                    who.map(OfflinePlayer::getUniqueId).orElse(null),
                                    who.map(OfflinePlayer::getName).orElse(null),
                                    data);
                },
                "harbor emit [external] event %string% [for %-offlineplayer%] [with data"
                        + " %-strings%]");
    }

    // ---------------------------------------------------------------- Rigs

    private static Optional<RigInstance> rig(Ctx c, int index) {
        Optional<UUID> id =
                c.string(index)
                        .flatMap(
                                s -> {
                                    try {
                                        return Optional.of(UUID.fromString(s.strip()));
                                    } catch (IllegalArgumentException e) {
                                        return Optional.empty();
                                    }
                                });
        if (id.isEmpty()) {
            return Optional.empty();
        }
        for (RigInstance rig : api().rigs().instances()) {
            if (rig.id().equals(id.get())) {
                return rig.isValid() ? Optional.of(rig) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static void rigs(Registrar r) {
        Ext.effect(
                r,
                "spawn harbor rig",
                c -> {
                    Optional<RigId> id =
                            c.string(0).flatMap(s -> Ids.parse(s, RigId.PREFIX)).map(RigId::new);
                    Optional<Location> at = c.as(1, Location.class);
                    if (id.isEmpty()
                            || at.isEmpty()
                            || api().rigs().definition(id.get()).isEmpty()) {
                        c.set(5, List.of());
                        return;
                    }
                    RigOptions options =
                            c.has(4) ? RigOptions.privateTo(c.players(4)) : RigOptions.publicRig();
                    options = options.withAnimation(c.string(2).orElse(null));
                    double scale = c.number(3).map(Number::doubleValue).orElse(1.0);
                    if (scale > 0) {
                        options = options.withScale(scale);
                    }
                    RigInstance rig = api().rigs().spawn(id.get(), at.get(), options);
                    c.set(5, List.of(rig.id().toString()));
                },
                "spawn harbor rig %string% at %location% [with animation %-string%] [with scale"
                        + " %-number%] [(for|to) %-players%] [and (store|save) [the] (id|rig) in"
                        + " %-objects%]");
        // pattern 0 loop, 1 once
        Ext.effect(
                r,
                "play harbor rig animation",
                c ->
                        rig(c, 1)
                                .ifPresent(
                                        rig ->
                                                c.string(0)
                                                        .ifPresent(
                                                                anim -> {
                                                                    if (c.pattern() == 0) {
                                                                        rig.play(anim);
                                                                    } else {
                                                                        rig.playOnce(
                                                                                anim,
                                                                                rig.animation());
                                                                    }
                                                                })),
                "play animation %string% on harbor rig %string%",
                "play animation %string% once on harbor rig %string%");
        Ext.effect(
                r,
                "stop harbor rig animation",
                c -> rig(c, 0).ifPresent(RigInstance::stop),
                "stop animation of harbor rig %string%");
        Ext.effect(
                r,
                "remove harbor rig",
                c -> rig(c, 0).ifPresent(RigInstance::remove),
                "remove harbor rig %string%");
        Ext.effect(
                r,
                "move harbor rig",
                c ->
                        rig(c, 0)
                                .ifPresent(
                                        rig ->
                                                c.as(1, Location.class)
                                                        .ifPresent(
                                                                to -> {
                                                                    var ticks =
                                                                            c.duration(2)
                                                                                    .map(
                                                                                            d ->
                                                                                                    (int)
                                                                                                            (d
                                                                                                                            .toMillis()
                                                                                                                    / 50));
                                                                    if (ticks.isPresent()) {
                                                                        rig.moveTo(
                                                                                to,
                                                                                Math.max(
                                                                                        1,
                                                                                        ticks
                                                                                                .get()));
                                                                    } else {
                                                                        rig.moveTo(to);
                                                                    }
                                                                })),
                "move harbor rig %string% to %location% [over %-timespan%]");
        // pattern 0 show to players, 1 show to everyone, 2 hide from players
        Ext.effect(
                r,
                "harbor rig viewers",
                c ->
                        rig(c, 0)
                                .ifPresent(
                                        rig -> {
                                            switch (c.pattern()) {
                                                case 0 -> c.players(1).forEach(rig::addViewer);
                                                case 1 -> rig.showToAll();
                                                default -> c.players(1).forEach(rig::removeViewer);
                                            }
                                        }),
                "show harbor rig %string% to %players%",
                "show harbor rig %string% to everyone",
                "hide harbor rig %string% from %players%");
        Ext.effect(
                r,
                "harbor rig glow",
                c ->
                        rig(c, 1)
                                .ifPresent(
                                        rig ->
                                                rig.setPartGlow(
                                                        c.string(0).orElse(RigInstance.ALL_PARTS),
                                                        c.mark() == 0)),
                "make [part %-string% of] harbor rig %string% (0:glow|1:stop glowing)");
        Ext.expr(r, String.class, "harbor rig of entity")
                .register(
                        c ->
                                c.all(0).stream()
                                        .filter(Entity.class::isInstance)
                                        .flatMap(o -> api().rigs().byEntity((Entity) o).stream())
                                        .map(rig -> rig.id().toString())
                                        .toList(),
                        "[the] harbor rig [id] of %entities%");
        Ext.expr(r, String.class, "harbor rig type")
                .single()
                .register(
                        c -> rig(c, 0).map(rig -> List.of(rig.rig().toString())).orElse(List.of()),
                        "[the] harbor rig type of [rig] %string%");
        Ext.expr(r, String.class, "harbor rig animation")
                .single()
                .register(
                        c -> rig(c, 0).map(rig -> Args.one(rig.animation())).orElse(List.of()),
                        "[the] harbor rig animation of [rig] %string%");
        Ext.expr(r, Location.class, "harbor rig location")
                .single()
                .register(
                        c -> rig(c, 0).map(rig -> List.of(rig.location())).orElse(List.of()),
                        "[the] harbor rig location of [rig] %string%");
        Ext.expr(r, Entity.class, "harbor rig entities")
                .list()
                .register(
                        c -> rig(c, 0).map(RigInstance::entities).orElse(List.of()),
                        "[the] harbor rig entities of [rig] %string%");
        Ext.expr(r, String.class, "harbor rig ids")
                .list()
                .register(
                        c ->
                                c.pattern() == 0
                                        ? api().rigs().ids().stream().map(RigId::toString).toList()
                                        : api().rigs().instances().stream()
                                                .filter(RigInstance::isValid)
                                                .map(rig -> rig.id().toString())
                                                .toList(),
                        "[all] harbor rig types",
                        "[all] spawned harbor rigs");
        Ext.condition(
                r,
                "harbor rig valid",
                c -> rig(c, 0).isPresent(),
                "harbor rig %string% (is valid|exists)",
                "harbor rig %string% (isn't|is not) (valid|existing)");
    }
}
