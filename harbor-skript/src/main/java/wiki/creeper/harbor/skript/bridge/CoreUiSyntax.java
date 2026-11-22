package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.skript.ScriptArgs;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Core screen syntax (always registered): messages_ko.yml text with placeholders (chat, action bar,
 * title, as text), timed titles, ambient action bars, boss bars, dialogs (notice · confirm · choice
 * · input, results stored by awaiting effects) and Harbor sounds.
 */
public final class CoreUiSyntax {

    private CoreUiSyntax() {}

    private static final String PH = "[with [1:parsed] (placeholders|values) %-strings%]";

    /** Boss bars shown by scripts: player → (script bar id → bar). Main thread only. */
    private static final Map<UUID, Map<String, BossBar>> BARS = new HashMap<>();

    public static void register(Registrar r) {
        messages(r);
        bossBars(r);
        dialogs(r);
        sounds(r);
    }

    // ---------------------------------------------------------------- Messages

    private static TagResolver[] placeholders(Ctx c, int index) {
        return Shared.resolvers(c.strings(index), c.mark() == 1);
    }

    private static void messages(Registrar r) {
        Ext.effect(
                r,
                "send harbor message with placeholders",
                c -> {
                    Optional<String> key = c.string(0);
                    if (key.isEmpty()) {
                        return;
                    }
                    TagResolver[] ph = placeholders(c, 2);
                    for (Player p : c.players(1)) {
                        api().messages().send(p, key.get(), ph);
                    }
                },
                "send harbor message %string% to %players% with [1:parsed] (placeholders|values)"
                        + " %strings%");
        Ext.expr(r, String.class, "harbor message text")
                .single()
                .register(
                        c ->
                                c.string(0)
                                        .map(
                                                k ->
                                                        Shared.legacy(
                                                                api().messages()
                                                                        .get(
                                                                                k,
                                                                                placeholders(
                                                                                        c, 1))))
                                        .map(List::of)
                                        .orElse(List.of()),
                        "[the] harbor message text [of] %string% " + PH);
        Ext.condition(
                r,
                "harbor message exists",
                c -> c.string(0).map(k -> api().messages().has(k)).orElse(false),
                "harbor message %string% exists",
                "harbor message %string% (doesn't|does not) exist");
        Ext.effect(
                r,
                "show harbor actionbar message",
                c -> {
                    Optional<String> key = c.string(0);
                    if (key.isEmpty()) {
                        return;
                    }
                    Component text = api().messages().get(key.get(), placeholders(c, 1));
                    for (Player p : c.players(2)) {
                        api().ui().actionBar(p, text);
                    }
                },
                "show harbor action[ ]bar message %string% " + PH + " to %players%");
        Ext.effect(
                r,
                "show harbor title message",
                c -> {
                    Optional<String> key = c.string(0);
                    if (key.isEmpty()) {
                        return;
                    }
                    TagResolver[] ph = placeholders(c, 2);
                    Component title = api().messages().get(key.get(), ph);
                    Component sub =
                            c.string(1)
                                    .map(k -> api().messages().get(k, ph))
                                    .orElse(Component.empty());
                    title(c.players(3), title, sub, c.duration(4));
                },
                "show harbor title message %string% [with subtitle message %-string%] "
                        + PH
                        + " to %players% [for %-timespan%]");
        Ext.effect(
                r,
                "show timed harbor title",
                c ->
                        title(
                                c.players(2),
                                Shared.mini(c.string(0).orElse("")),
                                Shared.mini(c.string(1).orElse("")),
                                c.duration(3)),
                "show harbor title %string% [with subtitle %-string%] to %players% for"
                        + " %timespan%");
        Ext.effect(
                r,
                "show harbor ambient actionbar",
                c -> {
                    Component text = Shared.mini(c.string(0).orElse(""));
                    Duration quiet = c.duration(2).orElse(Duration.ZERO);
                    for (Player p : c.players(1)) {
                        api().ui().ambientActionBar(p, text, quiet);
                    }
                },
                "show harbor ambient action[ ]bar %string% to %players% with quiet time"
                        + " %timespan%");
    }

    private static void title(
            List<Player> players, Component title, Component sub, Optional<Duration> stay) {
        Duration d =
                stay.orElseGet(
                        () ->
                                Duration.ofMillis(
                                        Math.round(
                                                balance().number("skript.title_seconds", 2.5)
                                                        * 1000)));
        for (Player p : players) {
            api().ui().title(p, title, sub, Duration.ofMillis(250), d, Duration.ofMillis(500));
        }
    }

    private static Balance balance() {
        return api().content().snapshot().single(Balance.TYPE);
    }

    // ---------------------------------------------------------------- Boss bars

    private static void bossBars(Registrar r) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Harbor-Skript");
        if (plugin != null) {
            Bukkit.getPluginManager().registerEvents(new BarCleanup(), plugin);
        }
        Ext.effect(
                r,
                "show harbor bossbar",
                c -> {
                    Optional<String> id = c.string(0);
                    if (id.isEmpty()) {
                        return;
                    }
                    Component name = Shared.mini(c.string(1).orElse(""));
                    float progress = progress(c.number(2).orElse(1.0));
                    BossBar.Color color =
                            c.string(3).flatMap(CoreUiSyntax::color).orElse(BossBar.Color.WHITE);
                    for (Player p : c.players(4)) {
                        Map<String, BossBar> bars =
                                BARS.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
                        BossBar bar = bars.get(id.get());
                        if (bar == null) {
                            bars.put(id.get(), api().ui().showBossBar(p, name, progress, color));
                        } else {
                            bar.name(name);
                            bar.progress(progress);
                            bar.color(color);
                        }
                    }
                },
                "show harbor boss[ ]bar %string% with (title|name) %string% [with progress"
                        + " %-number%] [with colo[u]r %-string%] to %players%");
        Ext.effect(
                r,
                "set harbor bossbar progress",
                c -> {
                    Optional<String> id = c.string(0);
                    float progress = progress(c.number(2).orElse(0.0));
                    if (id.isEmpty()) {
                        return;
                    }
                    for (Player p : c.players(1)) {
                        BossBar bar = BARS.getOrDefault(p.getUniqueId(), Map.of()).get(id.get());
                        if (bar != null) {
                            bar.progress(progress);
                        }
                    }
                },
                "harbor set boss[ ]bar %string% progress (of|for) %players% to %number%");
        Ext.effect(
                r,
                "hide harbor bossbar",
                c -> {
                    Optional<String> id = c.pattern() == 0 ? c.string(0) : Optional.empty();
                    for (Player p : c.players(c.pattern() == 0 ? 1 : 0)) {
                        Map<String, BossBar> bars = BARS.get(p.getUniqueId());
                        if (bars == null) {
                            continue;
                        }
                        List<String> ids =
                                id.isPresent() ? List.of(id.get()) : new ArrayList<>(bars.keySet());
                        for (String key : ids) {
                            BossBar bar = bars.remove(key);
                            if (bar != null) {
                                api().ui().hideBossBar(p, bar);
                            }
                        }
                        if (bars.isEmpty()) {
                            BARS.remove(p.getUniqueId());
                        }
                    }
                },
                "hide harbor boss[ ]bar %string% (from|of|for) %players%",
                "hide all harbor boss[ ]bars (from|of|for) %players%");
    }

    /** Forgets a player's script boss bars on quit. */
    public static final class BarCleanup implements Listener {

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            BARS.remove(event.getPlayer().getUniqueId());
        }
    }

    private static float progress(Number n) {
        double d = n.doubleValue();
        return Double.isNaN(d) ? 0f : (float) Math.max(0, Math.min(1, d));
    }

    private static Optional<BossBar.Color> color(String raw) {
        try {
            return Optional.of(BossBar.Color.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Dialogs

    private static void dialogs(Registrar r) {
        Ext.effect(
                r,
                "show harbor notice",
                c -> {
                    Component title = Shared.mini(c.string(0).orElse(""));
                    List<Component> body = Shared.minis(c.strings(1));
                    for (Player p : c.players(2)) {
                        api().dialogs().notice(p, title, body);
                    }
                },
                "show harbor notice [dialog] %string% [with body %-strings%] to %players%");
        Ext.await(
                r,
                "show harbor confirm",
                new int[] {5},
                c -> {
                    Optional<Player> p = c.player(4);
                    if (p.isEmpty()) {
                        return null;
                    }
                    CompletableFuture<List<?>[]> result = new CompletableFuture<>();
                    api().dialogs()
                            .confirm(
                                    p.get(),
                                    Shared.mini(c.string(0).orElse("")),
                                    Shared.minis(c.strings(1)),
                                    Shared.mini(c.string(2).orElse("")),
                                    Shared.mini(c.string(3).orElse("")),
                                    yes -> result.complete(new List<?>[] {List.of(yes)}));
                    return result;
                },
                "show harbor confirm [dialog] %string% [with body %-strings%] with buttons"
                        + " %string% and %string% to %player% and (store|save) [the] answer in"
                        + " %objects%");
        Ext.await(
                r,
                "show harbor choice",
                new int[] {5},
                c -> {
                    Optional<Player> p = c.player(4);
                    List<String> options = c.strings(2);
                    if (p.isEmpty() || options.isEmpty()) {
                        return null;
                    }
                    List<Dialogs.Choice> choices = new ArrayList<>();
                    for (String option : options) {
                        choices.add(new Dialogs.Choice(Shared.mini(option), Component.empty()));
                    }
                    int columns = Math.max(1, c.number(3).map(Number::intValue).orElse(1));
                    CompletableFuture<List<?>[]> result = new CompletableFuture<>();
                    api().dialogs()
                            .choose(
                                    p.get(),
                                    Shared.mini(c.string(0).orElse("")),
                                    Shared.minis(c.strings(1)),
                                    choices,
                                    columns,
                                    index ->
                                            result.complete(
                                                    new List<?>[] {List.of((long) index + 1)}));
                    return result;
                },
                "show harbor choice [dialog] %string% [with body %-strings%] with (options|choices)"
                        + " %strings% [in %-number% columns] to %player% and (store|save) [the]"
                        + " answer in %objects%");
        Ext.await(
                r,
                "show harbor input",
                new int[] {8},
                c -> {
                    Optional<Player> p = c.player(7);
                    if (p.isEmpty()) {
                        return null;
                    }
                    int max = Math.max(1, c.number(4).map(Number::intValue).orElse(256));
                    CompletableFuture<List<?>[]> result = new CompletableFuture<>();
                    api().dialogs()
                            .input(
                                    p.get(),
                                    Shared.mini(c.string(0).orElse("")),
                                    Shared.minis(c.strings(1)),
                                    Shared.mini(c.string(2).orElse("")),
                                    c.string(3).orElse(""),
                                    max,
                                    c.mark() == 1,
                                    Shared.mini(c.string(5).orElse("")),
                                    Shared.mini(c.string(6).orElse("")),
                                    text -> result.complete(new List<?>[] {Args.one(text)}));
                    return result;
                },
                "show harbor [1:multiline] input [dialog] %string% [with body %-strings%] [with"
                        + " label %-string%] [with default %-string%] [with max length %-number%]"
                        + " with buttons %string% and %string% to %player% and (store|save) [the]"
                        + " (answer|input) in %objects%");
    }

    // ---------------------------------------------------------------- Sounds

    private static HarborSounds.Options soundOptions(Ctx c, int volumeAt, int pitchAt) {
        float volume = c.number(volumeAt).map(Number::floatValue).orElse(1f);
        float pitch = c.number(pitchAt).map(Number::floatValue).orElse(1f);
        return new HarborSounds.Options(null, Sound.Source.MASTER, volume, pitch);
    }

    private static void sounds(Registrar r) {
        Ext.effect(
                r,
                "play harbor sound",
                c -> {
                    Optional<String> key = c.string(0).map(ScriptArgs::soundKey);
                    if (key.isEmpty()) {
                        return;
                    }
                    HarborSounds.Options options = soundOptions(c, 2, 3);
                    if (c.pattern() == 0) {
                        for (Player p : c.players(1)) {
                            HarborSounds.play(p, key.get(), options);
                        }
                    } else {
                        for (Object o : c.all(1)) {
                            if (o instanceof Location at) {
                                HarborSounds.playAt(at, key.get(), options);
                            }
                        }
                    }
                },
                "play harbor sound %string% (to|for) %players% [with volume %-number%] [with pitch"
                        + " %-number%]",
                "play harbor sound %string% at %locations% [with volume %-number%] [with pitch"
                        + " %-number%]");
        Ext.effect(
                r,
                "stop harbor sound",
                c ->
                        c.string(0)
                                .map(ScriptArgs::soundKey)
                                .ifPresent(
                                        key -> {
                                            for (Player p : c.players(1)) {
                                                HarborSounds.stop(p, key);
                                            }
                                        }),
                "stop harbor sound %string% (for|of) %players%");
    }
}
