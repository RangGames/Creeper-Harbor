package wiki.creeper.harbor.quest.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.quest.api.QuestRewardSummary;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.logic.NotifyStyles;

/**
 * Quest notifications (accept, ready to turn in, complete, abandon, fail, tracking). How each is
 * shown is written comma-separated in balance.yml {@code quest.notify.<event>} ({@code
 * <event>_main} for main quests): chat · title · actionbar · none. Chat text uses the existing keys
 * (quest.accepted etc.), titles {@code quest.notify.title.<event>} · {@code
 * quest.notify.subtitle.<event>}, the action bar {@code quest.notify.actionbar.<event>}.
 */
final class QuestNotifier {

    /** Notification event. The key is the name used in balance.yml and messages_ko.yml. */
    enum Kind {
        ACCEPTED("accepted", "quest.accepted"),
        READY("ready", "quest.ready"),
        COMPLETED("completed", "quest.completed"),
        ABANDONED("abandoned", "quest.abandoned"),
        FAILED("failed", "quest.failed"),
        TRACKING("tracking", "quest.tracking");

        final String key;
        final String chatKey;

        Kind(String key, String chatKey) {
            this.key = key;
            this.chatKey = chatKey;
        }
    }

    private final QuestEngine engine;

    QuestNotifier(QuestEngine engine) {
        this.engine = engine;
    }

    void accepted(Player player, QuestDef def) {
        send(player, def, Kind.ACCEPTED, Kind.ACCEPTED.chatKey, List.of());
        player.playSound(player, Sound.UI_TOAST_IN, 1f, 1.2f);
    }

    /**
     * Became ready to turn in. An empty npc means a quest that completes immediately without a
     * turn-in NPC.
     */
    void ready(Player player, QuestDef def, String npc) {
        send(
                player,
                def,
                Kind.READY,
                npc.isEmpty() ? "quest.ready_self" : Kind.READY.chatKey,
                List.of(Placeholder.unparsed("npc", npc)));
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    void completed(Player player, QuestDef def, QuestRewardSummary summary) {
        send(
                player,
                def,
                Kind.COMPLETED,
                Kind.COMPLETED.chatKey,
                List.of(
                        Placeholder.unparsed("money", String.valueOf(summary.gold())),
                        Placeholder.unparsed("mulbineul", String.valueOf(summary.mulbineul())),
                        Placeholder.unparsed("exp", String.valueOf(summary.exp()))));
        HarborSounds.play(player, "harbor:ui.quest_complete"); // '15. Sound' 4.5 request completed
    }

    void abandoned(Player player, QuestDef def) {
        send(player, def, Kind.ABANDONED, Kind.ABANDONED.chatKey, List.of());
    }

    void failed(Player player, QuestDef def) {
        send(player, def, Kind.FAILED, Kind.FAILED.chatKey, List.of());
    }

    void tracking(Player player, QuestDef def) {
        send(player, def, Kind.TRACKING, Kind.TRACKING.chatKey, List.of());
    }

    private void send(
            Player player, QuestDef def, Kind kind, String chatKey, List<TagResolver> extra) {
        List<TagResolver> resolvers = new ArrayList<>(extra);
        resolvers.add(Placeholder.unparsed("name", def.name()));
        resolvers.add(
                Placeholder.unparsed(
                        "type",
                        QuestEngine.plain(
                                engine.api.messages().get("quest.type." + def.type().key()))));
        TagResolver all = TagResolver.resolver(resolvers);
        var messages = engine.api.messages();
        for (NotifyStyles.Style style : styles(def, kind)) {
            switch (style) {
                case CHAT -> messages.send(player, chatKey, all);
                case TITLE ->
                        engine.api
                                .ui()
                                .title(
                                        player,
                                        messages.get("quest.notify.title." + kind.key, all),
                                        messages.get("quest.notify.subtitle." + kind.key, all),
                                        Duration.ofMillis(250),
                                        Duration.ofMillis(
                                                Math.round(
                                                        engine.balance()
                                                                        .number(
                                                                                "quest.notify.title_seconds",
                                                                                2.5)
                                                                * 1000)),
                                        Duration.ofMillis(500));
                case ACTIONBAR ->
                        engine.api
                                .ui()
                                .actionBar(
                                        player,
                                        messages.get("quest.notify.actionbar." + kind.key, all));
            }
        }
    }

    /**
     * balance.yml setting. Main quests use {@code <event>_main} if present. Otherwise chat only
     * (previous behavior).
     */
    List<NotifyStyles.Style> styles(QuestDef def, Kind kind) {
        var balance = engine.balance();
        String base = "quest.notify." + kind.key;
        String raw =
                (def.main() ? balance.string(base + "_main") : java.util.Optional.<String>empty())
                        .or(() -> balance.string(base))
                        .orElse("chat");
        return NotifyStyles.parse(raw.toLowerCase(Locale.ROOT));
    }
}
