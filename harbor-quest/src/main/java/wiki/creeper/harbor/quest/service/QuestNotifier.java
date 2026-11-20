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
 * 퀘스트 알림 (수락·보고 가능·완료·포기·실패·추적). 어떤 형태로 보일지는 balance.yml {@code quest.notify.<사건>}(메인 퀘스트는 {@code
 * <사건>_main})에 쉼표로 적는다: chat · title · actionbar · none. 채팅 문구는 기존 키(quest.accepted 등), 타이틀은 {@code
 * quest.notify.title.<사건>} · {@code quest.notify.subtitle.<사건>}, 액션바는 {@code
 * quest.notify.actionbar.<사건>}.
 */
final class QuestNotifier {

    /** 알림 사건. 키는 balance.yml·messages_ko.yml 이름. */
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

    /** 보고할 수 있게 됨. npc가 비어 있으면 보고 NPC 없이 바로 완료되는 퀘스트. */
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
        HarborSounds.play(player, "harbor:ui.quest_complete"); // '15. 사운드' 4.5 의뢰 완료
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

    /** balance.yml 설정. 메인은 {@code <사건>_main}이 있으면 그것을 쓴다. 없으면 채팅만 (기존 동작). */
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
