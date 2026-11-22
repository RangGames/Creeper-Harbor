package wiki.creeper.harbor.quest.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.QuestMachine;
import wiki.creeper.harbor.quest.logic.TrackerLine;

/**
 * Tracker boss bar (proposed): always shows the tracked quest's name, current objective and
 * progress at the top of the screen. When it can be turned in, shows the turn-in NPC. Toggled by
 * the player flag {@value #OFF_FLAG} (/퀘스트 표시), server-wide by balance.yml quest.tracker.bossbar.
 * Hidden during cutscenes. Main thread only.
 */
public final class TrackerBar {

    /** If this flag is set, the boss bar is not shown to that player. */
    public static final String OFF_FLAG = "settings/quest_bossbar_off";

    private record Shown(BossBar bar, Component name, float progress, BossBar.Color color) {}

    private final QuestEngine engine;
    private final Map<UUID, Shown> shown = new HashMap<>();

    TrackerBar(QuestEngine engine) {
        this.engine = engine;
    }

    /**
     * Every 20 ticks (progress and tracking changes are reflected in the next update). The boss bar
     * is changed only when something changed.
     */
    void refresh() {
        boolean enabled = engine.balance().bool("quest.tracker.bossbar", true);
        for (HarborPlayer hp : engine.api.players().online()) {
            Player player = Bukkit.getPlayer(hp.uuid());
            if (player == null) {
                continue;
            }
            Optional<TrackerLine.View> view =
                    enabled && !hidden(player) ? view(player, hp) : Optional.empty();
            if (view.isEmpty()) {
                hide(player);
                continue;
            }
            show(player, view.get());
        }
    }

    private boolean hidden(Player player) {
        return engine.api.flags().hasPlayer(player.getUniqueId(), OFF_FLAG)
                || engine.deps.cutscenes().isPlaying(player);
    }

    /**
     * /퀘스트 표시: turns it off if on, on if off. The result (whether it is on) completes on the main
     * thread.
     */
    public CompletableFuture<Boolean> toggle(Player player) {
        UUID uuid = player.getUniqueId();
        boolean off = engine.api.flags().hasPlayer(uuid, OFF_FLAG);
        CompletableFuture<Void> write =
                off
                        ? engine.api.flags().unsetPlayer(uuid, OFF_FLAG)
                        : engine.api.flags().setPlayer(uuid, FlagService.Scope.PERM, OFF_FLAG);
        return write.thenApplyAsync(
                v -> {
                    refresh();
                    return off;
                },
                engine.tasks.main());
    }

    private Optional<TrackerLine.View> view(Player player, HarborPlayer hp) {
        QuestData data = hp.get(QuestEngine.COMPONENT);
        QuestId tracked = data.tracked();
        if (tracked == null) {
            return Optional.empty();
        }
        QuestEntry e = data.entries().get(tracked);
        Optional<QuestDef> def = engine.def(tracked);
        if (e == null || def.isEmpty() || !e.state().open()) {
            return Optional.empty();
        }
        QuestDef d = def.get();
        if (e.state() == QuestState.READY || e.state() == QuestState.COMPLETING) {
            String npc =
                    d.turnInNpc()
                            .map(
                                    (NpcId n) ->
                                            QuestEngine.plain(
                                                    engine.deps.npcs().displayName(player, n)))
                            .orElse("");
            return Optional.of(TrackerLine.ready(d.name(), npc, d.main()));
        }
        List<Objective> current = QuestMachine.current(d, e.progress());
        List<TrackerLine.Goal> goals =
                current.stream()
                        .map(
                                o ->
                                        new TrackerLine.Goal(
                                                engine.objectiveText(player, d, o),
                                                o.tracked() ? e.progress().count(o.key()) : -1,
                                                o.count()))
                        .toList();
        return Optional.of(TrackerLine.active(d.name(), goals, d.main()));
    }

    private void show(Player player, TrackerLine.View view) {
        var messages = engine.api.messages();
        Component name =
                switch (view.kind()) {
                    case READY ->
                            messages.get(
                                    view.npc().isEmpty()
                                            ? "quest.bossbar.ready_self"
                                            : "quest.bossbar.ready",
                                    Placeholder.unparsed("quest", view.quest()),
                                    Placeholder.unparsed("npc", view.npc()));
                    case ACTIVE ->
                            messages.get(
                                    view.value() < 0
                                            ? "quest.bossbar.goal"
                                            : "quest.bossbar.progress",
                                    Placeholder.unparsed("quest", view.quest()),
                                    Placeholder.unparsed("objective", view.objective()),
                                    Placeholder.unparsed("value", String.valueOf(view.value())),
                                    Placeholder.unparsed("count", String.valueOf(view.count())),
                                    Placeholder.unparsed(
                                            "more", view.more() > 0 ? "+" + view.more() : ""));
                };
        BossBar.Color color =
                view.kind() == TrackerLine.Kind.READY
                        ? BossBar.Color.GREEN
                        : view.main() ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        float progress = view.progress();
        UUID uuid = player.getUniqueId();
        Shown current = shown.get(uuid);
        if (current != null) {
            if (current.name().equals(name)
                    && current.progress() == progress
                    && current.color() == color) {
                return;
            }
            current.bar().name(name);
            current.bar().progress(progress);
            current.bar().color(color);
            shown.put(uuid, new Shown(current.bar(), name, progress, color));
            return;
        }
        BossBar bar = engine.api.ui().showBossBar(player, name, progress, color);
        shown.put(uuid, new Shown(bar, name, progress, color));
    }

    private void hide(Player player) {
        Shown current = shown.remove(player.getUniqueId());
        if (current != null) {
            engine.api.ui().hideBossBar(player, current.bar());
        }
    }

    void forget(UUID uuid) {
        Shown current = shown.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (current != null && player != null) {
            engine.api.ui().hideBossBar(player, current.bar());
        }
    }

    /** Plugin shutdown: hides all. */
    void clear() {
        for (UUID uuid : List.copyOf(shown.keySet())) {
            forget(uuid);
        }
    }
}
