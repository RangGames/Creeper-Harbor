package wiki.creeper.harbor.quest.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestRewardSummary;
import wiki.creeper.harbor.quest.api.event.ChapterCompletedEvent;
import wiki.creeper.harbor.quest.api.event.QuestCompletedEvent;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.CutsceneAt;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Progress;

/**
 * Completion step 5: QuestCompletedEvent, notification and sound, completion cutscene, tracking
 * switch, chapter clear and server-first clear announcements. Main thread only.
 */
final class CompletionEffects {

    private final QuestEngine engine;

    CompletionEffects(QuestEngine engine) {
        this.engine = engine;
    }

    QuestRewardSummary summary(QuestDef def, Progress.Snapshot snap, List<String> items) {
        return new QuestRewardSummary(
                snap.gold(),
                snap.mulbineul(),
                snap.exp(),
                snap.reputation(),
                items,
                def.rewards().title(),
                def.rewards().chronicle());
    }

    /** Step 5: event, sound, cutscene, chapter clear, board, markers. */
    void afterComplete(UUID uuid, QuestDef def, QuestEntry entry, QuestRewardSummary summary) {
        Bukkit.getPluginManager()
                .callEvent(
                        new QuestCompletedEvent(
                                uuid,
                                def.id(),
                                def.type().key(),
                                entry.progress().cycle(),
                                summary));
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            engine.notifier().completed(player, def, summary);
            HarborId cutscene = def.cutscene();
            if (cutscene != null && def.cutsceneAt().kind() == CutsceneAt.Kind.COMPLETE) {
                engine.playCutscene(player, cutscene);
            }
            engine.refreshMarkers(uuid);
            engine.refreshGauges(player, null);
        }
        QuestEntry tracked = null;
        Optional<HarborPlayer> hp = engine.api.players().get(uuid);
        if (hp.isPresent() && def.id().equals(hp.get().get(QuestEngine.COMPONENT).tracked())) {
            // when the tracked quest ends, switch to another quest in progress (main first)
            for (Map.Entry<QuestDef, QuestEntry> e : engine.open(hp.get())) {
                if (tracked == null || e.getKey().main()) {
                    tracked = e.getValue();
                }
            }
            hp.get()
                    .set(
                            QuestEngine.COMPONENT,
                            hp.get()
                                    .get(QuestEngine.COMPONENT)
                                    .withTracked(tracked == null ? null : tracked.quest()));
        }
        chapterCheck(uuid, def);
    }

    /**
     * ChapterCompletedEvent if it is the chapter's last quest, a harbor:announce announcement if it
     * is the server-first clear.
     */
    void chapterCheck(UUID uuid, QuestDef def) {
        if (!def.main()) {
            return;
        }
        ChronicleDef chronicle = engine.chronicleDef();
        Optional<ChronicleDef.Chapter> chapter =
                chronicle.chapters().stream()
                        .filter(c -> c.finalQuest().equals(def.id()))
                        .findFirst();
        if (chapter.isEmpty()) {
            return;
        }
        int number = chapter.get().chapter();
        Bukkit.getPluginManager().callEvent(new ChapterCompletedEvent(uuid, number, false));
        int season = engine.season();
        String name = Optional.ofNullable(Bukkit.getPlayer(uuid)).map(Player::getName).orElse("?");
        var _ =
                engine.api
                        .database()
                        .async(c -> QuestRepository.completedCount(c, def.id(), season))
                        .thenAcceptAsync(
                                count -> {
                                    if (count == 1) {
                                        Component msg =
                                                engine.api
                                                        .messages()
                                                        .get(
                                                                "quest.announce.first_chapter",
                                                                Placeholder.unparsed(
                                                                        "player", name),
                                                                Placeholder.unparsed(
                                                                        "chapter",
                                                                        chapter.get().name()));
                                        engine.api
                                                .bus()
                                                .publish(
                                                        Channels.ANNOUNCEMENTS,
                                                        new Channels.Announcement(
                                                                MiniMessage.miniMessage()
                                                                        .serialize(msg)));
                                    }
                                },
                                engine.tasks.main());
    }
}
