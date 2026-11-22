package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import ch.njol.skript.util.Date;
import ch.njol.skript.util.Timespan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.PlayerFlagChangedEvent;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.ScriptArgs;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Core player syntax (always registered): player data (exp, play time, first join), load and
 * transfer state, saving, admin reputation, modifiers and stats, and the remaining flag syntax
 * (seasonal server flags, flag change events).
 */
public final class CorePlayerSyntax {

    private CorePlayerSyntax() {}

    private static Optional<HarborPlayer> hp(Player p) {
        return api().players().get(p.getUniqueId());
    }

    public static void register(Registrar r) {
        playerData(r);
        modifiers(r);
        flags(r);
    }

    // ---------------------------------------------------------------- Player data

    private static void playerData(Registrar r) {
        Ext.expr(r, Number.class, "harbor exp")
                .register(
                        c ->
                                c.players(0).stream()
                                        .flatMap(p -> hp(p).stream())
                                        .map(h -> (Number) h.exp())
                                        .toList(),
                        "[the] harbor exp[erience] of %players%",
                        "%players%'[s] harbor exp[erience]");
        Ext.expr(r, Timespan.class, "harbor playtime")
                .register(
                        c ->
                                c.players(0).stream()
                                        .flatMap(p -> hp(p).stream())
                                        .map(h -> timespan(h.playtime()))
                                        .toList(),
                        "[the] harbor play[ ]time of %players%",
                        "%players%'[s] harbor play[ ]time");
        Ext.expr(r, Date.class, "harbor first join")
                .register(
                        c ->
                                c.players(0).stream()
                                        .flatMap(p -> hp(p).stream())
                                        .map(h -> Shared.date(h.firstJoin()))
                                        .toList(),
                        "[the] harbor first join [date] of %players%",
                        "%players%'[s] harbor first join [date]");

        Ext.condition(
                r,
                "harbor loaded",
                c -> all(c, p -> hp(p).isPresent()),
                "%players% (is|are) harbor loaded",
                "%players% (isn't|is not|aren't|are not) harbor loaded");
        Ext.condition(
                r,
                "harbor frozen",
                c -> all(c, p -> api().players().frozen(p.getUniqueId())),
                "%players% (is|are) harbor frozen",
                "%players% (isn't|is not|aren't|are not) harbor frozen");

        Ext.effect(
                r,
                "harbor save now",
                c -> {
                    for (Player p : c.players(0)) {
                        var _ = api().players().saveNow(p.getUniqueId());
                    }
                },
                "harbor save %players% now");
        Ext.effect(
                r,
                "harbor save soon",
                c -> {
                    for (Player p : c.players(0)) {
                        api().players().saveSoon(p.getUniqueId());
                    }
                },
                "harbor save %players% soon");

        Ext.await(
                r,
                "harbor find player",
                new int[] {1},
                c -> {
                    Optional<String> name = c.string(0).map(String::strip);
                    if (name.isEmpty() || name.get().isEmpty()) {
                        return null;
                    }
                    return Ext.value(
                            api().players()
                                    .find(name.get())
                                    .thenApply(
                                            ref ->
                                                    ref.map(x -> Shared.offline(x.uuid()))
                                                            .orElse(null)));
                },
                "harbor find [player] [named] %string% and (store|save) [it] in %objects%");

        // Admin: reputation is normally changed by NPC and quest rewards
        Ext.effect(
                r,
                "harbor admin reputation",
                c -> {
                    int playersAt = c.pattern() == 0 ? 0 : 1;
                    int amountAt = c.pattern() == 0 ? 1 : 0;
                    Optional<Number> amount = c.number(amountAt);
                    if (amount.isEmpty()) {
                        return;
                    }
                    int n = amount.get().intValue();
                    for (Player p : c.players(playersAt)) {
                        hp(p).ifPresent(
                                        h ->
                                                h.setReputation(
                                                        switch (c.mark()) {
                                                            case 1 -> h.reputation() + n;
                                                            case 2 -> h.reputation() - n;
                                                            default -> n;
                                                        }));
                    }
                },
                "harbor admin set reputation of %players% to %number%",
                "harbor admin (1:add|2:remove) %number% reputation (to|from) %players%");
    }

    // ---------------------------------------------------------------- Modifiers

    private static void modifiers(Registrar r) {
        // pattern 0: add value, 1: multiply. Indices differ per pattern.
        Ext.effect(
                r,
                "harbor add modifier",
                c -> {
                    boolean add = c.pattern() == 0;
                    Optional<Modifier> mod =
                            modifier(
                                    c,
                                    add,
                                    add ? 1 : 0,
                                    add ? 0 : 2,
                                    3,
                                    4,
                                    add ? Modifier.Op.ADD : Modifier.Op.MULTIPLY);
                    if (mod.isEmpty()) {
                        return;
                    }
                    for (Player p : c.players(add ? 2 : 1)) {
                        api().modifiers().add(p.getUniqueId(), mod.get());
                    }
                },
                "harbor add %number% to stat %string% of %players% (from|with) source %string%"
                        + " [for %-timespan%]",
                "harbor multiply stat %string% of %players% by %number% (from|with) source"
                        + " %string% [for %-timespan%]");
        Ext.effect(
                r,
                "harbor add global modifier",
                c -> {
                    boolean add = c.pattern() == 0;
                    modifier(
                                    c,
                                    add,
                                    add ? 1 : 0,
                                    add ? 0 : 1,
                                    2,
                                    3,
                                    add ? Modifier.Op.ADD : Modifier.Op.MULTIPLY)
                            .ifPresent(m -> api().modifiers().addGlobal(m));
                },
                "harbor add %number% to global stat %string% (from|with) source %string% [for"
                        + " %-timespan%]",
                "harbor multiply global stat %string% by %number% (from|with) source %string%"
                        + " [for %-timespan%]");
        Ext.effect(
                r,
                "harbor remove modifiers",
                c ->
                        c.string(0)
                                .flatMap(ScriptArgs::source)
                                .ifPresent(
                                        source -> {
                                            for (Player p : c.players(1)) {
                                                api().modifiers()
                                                        .removeSource(p.getUniqueId(), source);
                                            }
                                        }),
                "harbor remove modifiers (from|with) source %string% (from|of) %players%");
        Ext.effect(
                r,
                "harbor remove global modifiers",
                c ->
                        c.string(0)
                                .flatMap(ScriptArgs::source)
                                .ifPresent(s -> api().modifiers().removeGlobalSource(s)),
                "harbor remove global modifiers (from|with) source %string%");

        Ext.expr(r, Number.class, "harbor stat")
                .perArg(1)
                .register(
                        c -> {
                            Optional<StatKey> stat = stat(c.string(0).orElse(""));
                            if (stat.isEmpty()) {
                                return List.of();
                            }
                            Optional<Number> base =
                                    c.pattern() == 1 ? c.number(2) : Optional.empty();
                            if (c.pattern() == 1 && base.isEmpty()) {
                                return List.of();
                            }
                            return c.players(1).stream()
                                    .map(
                                            p ->
                                                    (Number)
                                                            (base.isPresent()
                                                                    ? api().modifiers()
                                                                            .apply(
                                                                                    p.getUniqueId(),
                                                                                    stat.get(),
                                                                                    base.get()
                                                                                            .doubleValue())
                                                                    : api().modifiers()
                                                                            .value(
                                                                                    p.getUniqueId(),
                                                                                    stat.get())))
                                    .toList();
                        },
                        "[the] harbor stat %string% of %players%",
                        "[the] harbor stat %string% of %players% with base %number%");
    }

    /**
     * Builds a script modifier.
     *
     * @param statAt index of the stat name
     * @param valueAt index of the value
     */
    private static Optional<Modifier> modifier(
            Ctx c, boolean add, int statAt, int valueAt, int sourceAt, int timeAt, Modifier.Op op) {
        Optional<StatKey> stat = stat(c.string(statAt).orElse(""));
        Optional<Number> value = c.number(valueAt);
        Optional<String> source = c.string(sourceAt).flatMap(ScriptArgs::source);
        if (stat.isEmpty() || value.isEmpty() || source.isEmpty()) {
            return Optional.empty();
        }
        double v = value.get().doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v) || (!add && v < 0)) {
            return Optional.empty();
        }
        Modifier m = new Modifier(stat.get(), op, v, source.get(), null);
        Optional<Duration> time = c.duration(timeAt);
        return Optional.of(time.isPresent() ? m.until(Instant.now().plus(time.get())) : m);
    }

    private static Optional<StatKey> stat(String raw) {
        try {
            return Optional.of(StatKey.of(raw.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Flags (rest)

    private static void flags(Registrar r) {
        Ext.effect(
                r,
                "set seasonal harbor server flag",
                c -> {
                    String flag = c.string(0).orElse("");
                    if (Ids.flag(flag)) {
                        var _ = api().flags().setServer(flag, c.string(1).orElse("1"), true);
                    }
                },
                "harbor set seasonal server flag %string% [to %-string%]");

        r.event(
                "Harbor Player Flag Change",
                PlayerFlagChangedEvent.class,
                "플레이어 플래그가 DB에 저장된 뒤 (event-offlineplayer, event-string = 플래그 이름, harbor"
                        + " changed flag value = 새 값, 꺼졌으면 값 없음)",
                "harbor [player] flag change");
        Ext.playerValues(r, PlayerFlagChangedEvent.class, PlayerFlagChangedEvent::player);
        r.value(PlayerFlagChangedEvent.class, String.class, PlayerFlagChangedEvent::flag);

        r.event(
                "Harbor Server Flag Change",
                ServerFlagChangedEvent.class,
                "서버 플래그가 바뀐 뒤 (다른 서버에서 바꾼 것 포함. event-string = 플래그 이름)",
                "harbor server flag change");
        r.value(ServerFlagChangedEvent.class, String.class, ServerFlagChangedEvent::flag);

        Ext.expr(r, String.class, "harbor changed flag value")
                .single()
                .register(
                        c ->
                                switch (c.event()) {
                                    case PlayerFlagChangedEvent e -> Args.one(e.value());
                                    case ServerFlagChangedEvent e -> Args.one(e.value());
                                    default -> List.of();
                                },
                        "[the] harbor changed flag value");
    }

    // ---------------------------------------------------------------- helpers

    private static boolean all(Ctx c, java.util.function.Predicate<Player> test) {
        List<Player> players = c.players(0);
        return !players.isEmpty() && players.stream().allMatch(test);
    }

    private static Timespan timespan(Duration d) {
        return new Timespan(Timespan.TimePeriod.MILLISECOND, Math.max(0, d.toMillis()));
    }
}
