package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;
import wiki.creeper.harbor.quest.logic.TrackerLine;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * Quest book icons: name with progress, and a tooltip in short sections (summary, objectives with
 * the current one highlighted and a bar, rewards one per line without zero values, controls). Main
 * thread only.
 */
final class QuestBookIcons {

    private final HarborApi api;
    private final QuestServices q;

    QuestBookIcons(HarborApi api, QuestServices q) {
        this.api = api;
        this.q = q;
    }

    private MessageService msg() {
        return api.messages();
    }

    private QuestEngine engine() {
        return q.engine();
    }

    private Balance balance() {
        return api.content().snapshot().single(Balance.TYPE);
    }

    ItemStack icon(Player player, HarborPlayer hp, QuestDef def, QuestBook.Tab tab) {
        Optional<QuestEntry> entry = hp.get(QuestEngine.COMPONENT).entry(def.id());
        QuestState state = engine().avail(hp, def).state();
        QuestId tracked = hp.get(QuestEngine.COMPONENT).tracked();
        boolean isTracked = tab == QuestBook.Tab.ACTIVE && def.id().equals(tracked);
        boolean completed = tab == QuestBook.Tab.COMPLETED;
        Optional<Progress> progress = entry.map(QuestEntry::progress);
        List<Objective> current = progress.map(p -> QuestMachine.current(def, p)).orElse(List.of());

        List<Component> lore = new ArrayList<>();
        lore.add(
                msg().get(
                                "quest.book.type",
                                Placeholder.component(
                                        "type", msg().get("quest.type." + def.type().key()))));
        for (String line : def.description()) {
            lore.add(msg().get("quest.book.description", Placeholder.unparsed("text", line)));
        }

        // Objectives: done count in the header, the current one with its bar
        int done = 0;
        List<Component> objectiveLines = new ArrayList<>();
        for (Objective o : def.objectives()) {
            int value = completed ? o.count() : progress.map(p -> p.count(o.key())).orElse(0);
            boolean isDone = completed || (o.tracked() && value >= o.count());
            if (isDone) {
                done++;
            }
            boolean now = !completed && !isDone && current.contains(o);
            objectiveLines.addAll(objective(player, def, o, value, isDone, now));
        }
        lore.add(Component.empty());
        lore.add(
                msg().get(
                                "quest.book.objectives",
                                Placeholder.unparsed("done", String.valueOf(done)),
                                Placeholder.unparsed(
                                        "total", String.valueOf(def.objectives().size()))));
        lore.addAll(objectiveLines);

        List<Component> rewards = rewards(hp, def, progress);
        if (!rewards.isEmpty()) {
            lore.add(Component.empty());
            lore.add(msg().get("quest.book.rewards"));
            lore.addAll(rewards);
        }

        lore.add(Component.empty());
        lore.addAll(hints(player, def, tab, state, isTracked));

        String nameKey =
                switch (state) {
                    case READY -> "quest.book.name_ready";
                    case COMPLETED -> "quest.book.name_completed";
                    default -> def.main() ? "quest.book.name_main" : "quest.book.name";
                };
        Component name =
                msg().get(
                                nameKey,
                                Placeholder.unparsed("name", def.name()),
                                Placeholder.unparsed("done", String.valueOf(done)),
                                Placeholder.unparsed(
                                        "total", String.valueOf(def.objectives().size())));
        if (isTracked) {
            name = name.append(msg().get("quest.book.name_tracked"));
        }
        return MenuIcons.icon(
                material(def, state), name, lore, isTracked || state == QuestState.READY);
    }

    /** One objective: ✔ done · ▶ current (with a progress bar for counted goals) · upcoming. */
    private List<Component> objective(
            Player player, QuestDef def, Objective o, int value, boolean done, boolean now) {
        String text = engine().objectiveText(player, def, o);
        String key =
                done
                        ? "quest.book.objective_done"
                        : now ? "quest.book.objective_now" : "quest.book.objective";
        List<Component> lines = new ArrayList<>();
        lines.add(
                msg().get(
                                key,
                                Placeholder.unparsed("text", text),
                                Placeholder.unparsed(
                                        "value", o.tracked() ? String.valueOf(value) : "-"),
                                Placeholder.unparsed("count", String.valueOf(o.count()))));
        if (now && o.tracked() && o.count() > 1) {
            lines.add(
                    msg().get(
                                    "quest.book.objective_bar",
                                    Placeholder.component("bar", bar(value, o.count())),
                                    Placeholder.unparsed(
                                            "percent",
                                            String.valueOf(
                                                    Math.min(100, value * 100 / o.count())))));
        }
        return lines;
    }

    /** Reward lines, skipping zero values. */
    private List<Component> rewards(HarborPlayer hp, QuestDef def, Optional<Progress> progress) {
        Progress.Snapshot snap = progress.map(Progress::snap).orElseGet(() -> q.preview(hp, def));
        List<Component> lines = new ArrayList<>();
        reward(lines, "quest.book.reward.gold", snap.gold());
        reward(lines, "quest.book.reward.mulbineul", snap.mulbineul());
        reward(lines, "quest.book.reward.exp", snap.exp());
        reward(lines, "quest.book.reward.reputation", snap.reputation());
        if (!def.rewards().items().isEmpty()
                || def.rewards().title() != null
                || def.rewards().chronicle() != null) {
            lines.add(msg().get("quest.book.reward.extra"));
        }
        return lines;
    }

    private void reward(List<Component> lines, String key, long value) {
        if (value > 0) {
            lines.add(msg().get(key, Placeholder.unparsed("value", String.valueOf(value))));
        }
    }

    /**
     * ■■■□□ (balance.yml quest.book.bar_width, characters from messages
     * quest.book.bar_full/bar_empty).
     */
    private Component bar(int value, int count) {
        int width = Math.max(1, balance().integer("quest.book.bar_width", 10));
        int filled = TrackerLine.filledCells(value, count, width);
        Component full = msg().get("quest.book.bar_full");
        Component empty = msg().get("quest.book.bar_empty");
        Component out = Component.empty();
        for (int i = 0; i < width; i++) {
            out = out.append(i < filled ? full : empty);
        }
        return out;
    }

    private List<Component> hints(
            Player player, QuestDef def, QuestBook.Tab tab, QuestState state, boolean tracked) {
        List<Component> lines = new ArrayList<>();
        switch (tab) {
            case ACTIVE -> {
                if (state == QuestState.READY) {
                    Optional<NpcId> npc = def.turnInNpc();
                    lines.add(
                            npc.isPresent()
                                    ? msg().get(
                                                    "quest.book.hint.report_to",
                                                    Placeholder.component(
                                                            "npc", q.npcName(player, npc.get())))
                                    : msg().get("quest.book.hint.report"));
                } else if (!tracked) {
                    lines.add(msg().get("quest.book.hint.track"));
                }
                lines.add(msg().get("quest.book.hint.detail"));
                if (def.abandonable() && !def.main()) {
                    lines.add(msg().get("quest.book.hint.abandon"));
                }
            }
            case AVAILABLE -> {
                NpcId start = def.startNpc();
                lines.add(
                        start == null
                                ? msg().get("quest.book.hint.accept")
                                : msg().get(
                                                "quest.book.hint.start_npc",
                                                Placeholder.component(
                                                        "npc", q.npcName(player, start))));
            }
            case COMPLETED -> lines.add(msg().get("quest.book.hint.completed"));
        }
        return lines;
    }

    private Material material(QuestDef def, QuestState state) {
        Balance b = balance();
        if (state == QuestState.READY) {
            return MenuIcons.material(b, "ready", Material.EMERALD);
        }
        return switch (def.type()) {
            case MAIN -> MenuIcons.material(b, "main", Material.NETHER_STAR);
            case REGION -> MenuIcons.material(b, "region", Material.FILLED_MAP);
            case JOB -> MenuIcons.material(b, "job", Material.FISHING_ROD);
            case DAILY, WEEKLY -> MenuIcons.material(b, "board_quest", Material.PAPER);
            case COLLECT -> MenuIcons.material(b, "collect", Material.TROPICAL_FISH_BUCKET);
            case HIDDEN -> MenuIcons.material(b, "hidden", Material.ECHO_SHARD);
            case EVENT -> MenuIcons.material(b, "event", Material.FIREWORK_ROCKET);
        };
    }
}
