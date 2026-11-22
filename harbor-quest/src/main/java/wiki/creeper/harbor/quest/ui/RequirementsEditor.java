package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.quest.editor.QuestDraft;

/** Requirements screen of the quest editor (level, job tier, week, prerequisite quests). */
final class RequirementsEditor {

    private final HarborApi api;
    private final EditorPickers pickers;
    private final QuestEditor editor;

    RequirementsEditor(HarborApi api, EditorPickers pickers, QuestEditor editor) {
        this.api = api;
        this.pickers = pickers;
        this.editor = editor;
    }

    private MessageService msg() {
        return api.messages();
    }

    private Component text(String key, TagResolver... resolvers) {
        return editor.text(key, resolvers);
    }

    List<Component> requiresLore(QuestDraft d) {
        Map<String, Object> r = d.section("requires");
        List<Component> lore = new ArrayList<>();
        lore.add(
                text(
                        "requires.level",
                        Placeholder.unparsed(
                                "value", String.valueOf(QuestDraft.integer(r, "level", 0)))));
        lore.add(
                text(
                        "requires.job_tier",
                        Placeholder.unparsed(
                                "value", String.valueOf(QuestDraft.integer(r, "job_tier", 0)))));
        lore.add(
                text(
                        "requires.week",
                        Placeholder.unparsed(
                                "value", String.valueOf(QuestDraft.integer(r, "week", 0)))));
        for (Object quest : d.list("requires", "quests")) {
            lore.add(
                    text("requires.quest_line", Placeholder.unparsed("id", String.valueOf(quest))));
        }
        lore.add(text("hint.open"));
        return lore;
    }

    void requires(Player admin, QuestDraft d) {
        Map<String, Object> r = d.section("requires");
        Menu menu = new Menu(text("title.requires"), 3);
        requireNumber(
                menu,
                0,
                admin,
                d,
                Material.EXPERIENCE_BOTTLE,
                "level",
                1,
                5,
                QuestDraft.integer(r, "level", 0));
        requireNumber(
                menu,
                1,
                admin,
                d,
                Material.FISHING_ROD,
                "job_tier",
                1,
                1,
                QuestDraft.integer(r, "job_tier", 0));
        requireNumber(
                menu, 2, admin, d, Material.CLOCK, "week", 1, 1, QuestDraft.integer(r, "week", 0));
        menu.button(
                4,
                MenuIcons.icon(Material.LIME_DYE, text("requires.add_quest"), List.of(), false),
                e ->
                        pickers.pick(
                                admin,
                                text("title.pick_quest"),
                                pickers.quests(d.id()),
                                v -> {
                                    List<Object> quests = d.list("requires", "quests");
                                    if (!quests.contains(v)) {
                                        quests.add(v);
                                        d.touch();
                                    }
                                    requires(admin, d);
                                },
                                () -> requires(admin, d)));
        int slot = 9;
        for (Object quest : List.copyOf(d.list("requires", "quests"))) {
            if (slot >= 18) {
                break;
            }
            menu.button(
                    slot++,
                    MenuIcons.icon(
                            Material.BOOK,
                            text(
                                    "requires.quest_line",
                                    Placeholder.unparsed("id", String.valueOf(quest))),
                            List.of(text("hint.remove")),
                            false),
                    e -> {
                        d.list("requires", "quests").remove(quest);
                        d.touch();
                        requires(admin, d);
                    });
        }
        menu.button(
                18,
                MenuIcons.icon(Material.ARROW, msg().get("quest.dialog.back")),
                e -> editor.main(admin, d));
        menu.open(admin);
    }

    private void requireNumber(
            Menu menu,
            int slot,
            Player admin,
            QuestDraft d,
            Material icon,
            String key,
            int small,
            int big,
            int value) {
        menu.button(
                slot,
                MenuIcons.icon(
                        icon,
                        text(
                                "requires." + key,
                                Placeholder.unparsed("value", String.valueOf(value))),
                        List.of(
                                text(
                                        "hint.number",
                                        Placeholder.unparsed("small", String.valueOf(small)),
                                        Placeholder.unparsed("big", String.valueOf(big)))),
                        false),
                e -> {
                    d.add("requires", key, QuestEditor.step(e.getClick(), small, big));
                    requires(admin, d);
                });
    }
}
