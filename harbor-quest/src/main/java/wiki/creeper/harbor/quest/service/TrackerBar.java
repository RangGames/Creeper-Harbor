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
 * 추적 보스바 (추가 제안): 추적 중인 퀘스트의 이름·현재 목표·진행률을 화면 위에 늘 띄운다. 보고할 수 있으면 보고 NPC를 보여 준다. 끄고 켜기는 플레이어 플래그
 * {@value #OFF_FLAG} (/퀘스트 표시), 서버 전체는 balance.yml quest.tracker.bossbar. 컷씬 중에는 숨긴다. 메인 스레드 전용.
 */
public final class TrackerBar {

    /** 이 플래그가 있으면 그 플레이어에게 보스바를 띄우지 않는다. */
    public static final String OFF_FLAG = "settings/quest_bossbar_off";

    private record Shown(BossBar bar, Component name, float progress, BossBar.Color color) {}

    private final QuestEngine engine;
    private final Map<UUID, Shown> shown = new HashMap<>();

    TrackerBar(QuestEngine engine) {
        this.engine = engine;
    }

    /** 20틱마다 (진행·추적 변경도 다음 갱신에 반영된다). 바뀐 경우에만 보스바를 고친다. */
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

    /** /퀘스트 표시: 켜져 있으면 끄고, 꺼져 있으면 켠다. 결과(켜짐 여부)는 메인 스레드. */
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

    /** 플러그인 종료: 모두 숨긴다. */
    void clear() {
        for (UUID uuid : List.copyOf(shown.keySet())) {
            forget(uuid);
        }
    }
}
