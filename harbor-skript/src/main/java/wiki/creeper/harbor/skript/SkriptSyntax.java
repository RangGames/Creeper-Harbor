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

/**
 * Harbor 문법 등록 (Skript 2.17 등록 API).
 *
 * <pre>
 * harbor gold of %players%            골드 (이 서버 접속자). add · remove · set 가능 (원장 SCRIPT_GRANT · SCRIPT_TAKE)
 * harbor mulbineul of %players%       물비늘 (같음)
 * harbor item %string%                Harbor 아이템 (item/x, furniture/x, block/x)
 * harbor id of %itemstacks%           아이템의 Harbor ID (어종이면 fish/x)
 * %itemstacks% is [a] harbor item     Harbor 아이템인지
 * send harbor message %string% to %players%   messages_ko.yml 문구
 * harbor send %players% to lobby     로비로 (자리 유예, 자동 출항 안 함)
 * harbor send %players% to sea       출항 = 로비에서 본 서버 대기열로
 * harbor server · harbor server role 이 서버 ID · 역할
 * on harbor currency change           골드·물비늘이 바뀐 뒤 (event-offlineplayer, event-number = 변화량, event-string = gold·mulbineul)
 * </pre>
 */
final class SkriptSyntax {

    private SkriptSyntax() {}

    /**
     * @return 등록한 문법 수
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
        return count;
    }
}
