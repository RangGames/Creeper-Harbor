package wiki.creeper.harbor.skript;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.skriptlang.skript.Skript;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValue;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValueRegistry;
import org.skriptlang.skript.bukkit.registration.BukkitSyntaxInfos;
import org.skriptlang.skript.registration.DefaultSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;
import wiki.creeper.harbor.core.api.event.CurrencyChangedEvent;
import wiki.creeper.harbor.skript.bridge.CollectionSyntax;
import wiki.creeper.harbor.skript.bridge.CoreSyntax;
import wiki.creeper.harbor.skript.bridge.EconomySyntax;
import wiki.creeper.harbor.skript.bridge.FishingSyntax;
import wiki.creeper.harbor.skript.bridge.JobsSyntax;
import wiki.creeper.harbor.skript.bridge.NpcSyntax;
import wiki.creeper.harbor.skript.bridge.QuestSyntax;
import wiki.creeper.harbor.skript.bridge.SeasonSyntax;
import wiki.creeper.harbor.skript.bridge.WorldSyntax;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Harbor syntax registration (Skript 2.17 registration API).
 *
 * <pre>
 * harbor gold of %players%            gold (players on this server). add · remove · set allowed (ledger SCRIPT_GRANT · SCRIPT_TAKE)
 * harbor mulbineul of %players%       mulbineul (same)
 * harbor item %string%                Harbor item (item/x, furniture/x, block/x)
 * harbor id of %itemstacks%           Harbor ID of an item (fish/x for fish)
 * %itemstacks% is [a] harbor item     whether it is a Harbor item
 * send harbor message %string% to %players%   messages_ko.yml text
 * harbor send %players% to lobby     to the lobby (slot grace period, no automatic sailing)
 * harbor send %players% to sea       set sail = from the lobby to the main server queue
 * harbor server · harbor server role this server's ID · role
 * on harbor currency change           after gold or mulbineul changes (event-offlineplayer, event-number = change, event-string = gold·mulbineul)
 * </pre>
 *
 * <p>Other syntax (levels, flags, screens, mail, and per-module quest, fishing, collection, NPC,
 * season, market price and sea event syntax) is registered as lambda syntax by {@code
 * bridge/*Syntax}. The full list is in harbor-skript/README.md.
 */
final class SkriptSyntax {

    private SkriptSyntax() {}

    /**
     * @return number of registered syntax elements
     */
    static int register(JavaPlugin plugin) {
        Skript skript = ch.njol.skript.Skript.instance();
        SkriptAddon addon = skript.registerAddon(plugin.getClass(), plugin.getName());
        SyntaxRegistry registry = addon.syntaxRegistry();
        int count = 0;

        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(ExprBalance.class, Long.class)
                        .addPatterns(
                                "[the] harbor (0:gold|1:mulbineul) of %players%",
                                "%players%'[s] harbor (0:gold|1:mulbineul)")
                        .supplier(ExprBalance::new)
                        .build());
        count++;
        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(
                                ExprItem.class, org.bukkit.inventory.ItemStack.class)
                        .addPatterns("harbor item %string%")
                        .supplier(ExprItem::new)
                        .build());
        count++;
        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(ExprId.class, String.class)
                        .addPatterns(
                                "[the] harbor id of %itemstacks%", "%itemstacks%'[s] harbor id")
                        .supplier(ExprId::new)
                        .build());
        count++;
        registry.register(
                SyntaxRegistry.CONDITION,
                SyntaxInfo.builder(CondHarborItem.class)
                        .addPatterns(
                                "%itemstacks% (is|are) [a] harbor item[s]",
                                "%itemstacks% (isn't|is not|aren't|are not) [a] harbor item[s]")
                        .supplier(CondHarborItem::new)
                        .build());
        count++;
        registry.register(
                SyntaxRegistry.EFFECT,
                SyntaxInfo.builder(EffMessage.class)
                        .addPatterns("send harbor message %string% to %players%")
                        .supplier(EffMessage::new)
                        .build());
        count++;
        registry.register(
                BukkitSyntaxInfos.Event.KEY,
                BukkitSyntaxInfos.Event.builder(EvtCurrencyChange.class, "Harbor Currency Change")
                        .addEvent(CurrencyChangedEvent.class)
                        .addPatterns("harbor currency change")
                        .addDescription("골드·물비늘이 바뀐 뒤 (원장에 기록된 뒤, 이 서버에서 처리한 변경)")
                        .supplier(EvtCurrencyChange::new)
                        .build());
        count++;
        registry.register(
                SyntaxRegistry.EFFECT,
                SyntaxInfo.builder(EffMove.class)
                        .addPatterns("harbor send %players% to (0:[the] lobby|1:[the] sea)")
                        .supplier(EffMove::new)
                        .build());
        count++;
        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(ExprServer.class, String.class)
                        .addPatterns("[the] harbor server", "[the] harbor server role")
                        .supplier(ExprServer::new)
                        .build());
        count++;
        EventValueRegistry values = skript.registry(EventValueRegistry.class);
        values.register(
                EventValue.simple(
                        CurrencyChangedEvent.class,
                        OfflinePlayer.class,
                        e -> Bukkit.getOfflinePlayer(e.player())));
        values.register(
                EventValue.simple(CurrencyChangedEvent.class, Number.class, e -> e.delta()));
        values.register(
                EventValue.simple(
                        CurrencyChangedEvent.class, String.class, e -> e.currency().key()));

        // lambda syntax: Core always, game modules only when that module is enabled (the bridge
        // class is loaded then)
        Registrar r = new Registrar(registry, values);
        CoreSyntax.register(r);
        module(plugin, "Harbor-Npc", () -> NpcSyntax.register(r));
        module(plugin, "Harbor-Jobs", () -> JobsSyntax.register(r));
        module(plugin, "Harbor-Fishing", () -> FishingSyntax.register(r));
        module(plugin, "Harbor-Collection", () -> CollectionSyntax.register(r));
        module(plugin, "Harbor-Quest", () -> QuestSyntax.register(r));
        module(plugin, "Harbor-Season", () -> SeasonSyntax.register(r));
        module(plugin, "Harbor-Economy", () -> EconomySyntax.register(r));
        module(plugin, "Harbor-World", () -> WorldSyntax.register(r));
        return count + r.count();
    }

    /**
     * Registers the syntax if that module is enabled. A failure in one module (version mismatch
     * etc.) does not block the rest.
     */
    private static void module(JavaPlugin plugin, String name, Runnable register) {
        if (!Bukkit.getPluginManager().isPluginEnabled(name)) {
            return;
        }
        try {
            register.run();
        } catch (LinkageError | RuntimeException e) {
            plugin.getSLF4JLogger().warn("{} 문법 등록 실패: {}", name, String.valueOf(e));
        }
    }
}
