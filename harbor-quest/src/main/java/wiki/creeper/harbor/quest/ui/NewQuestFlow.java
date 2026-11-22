package wiki.creeper.harbor.quest.ui;

import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.editor.EditorStore;
import wiki.creeper.harbor.quest.editor.QuestDraft;

/** New quest flow of the quest editor: type, then ID, then name. */
final class NewQuestFlow {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,40}");

    private final HarborApi api;
    private final EditorStore store;
    private final QuestEditor editor;

    NewQuestFlow(HarborApi api, EditorStore store, QuestEditor editor) {
        this.api = api;
        this.store = store;
        this.editor = editor;
    }

    private MessageService msg() {
        return api.messages();
    }

    private Component text(String key, TagResolver... resolvers) {
        return editor.text(key, resolvers);
    }

    void chooseType(Player admin) {
        Menu menu = new Menu(text("title.new_type"), 2);
        int slot = 0;
        for (QuestType type : QuestType.values()) {
            menu.button(
                    slot++,
                    MenuIcons.icon(
                            QuestEditor.typeIcon(type),
                            msg().get("quest.type." + type.key()),
                            List.of(
                                    text(
                                            "new.type_hint",
                                            Placeholder.unparsed("file", QuestDraft.fileFor(type)),
                                            Placeholder.unparsed("type", type.key()))),
                            false),
                    e -> askId(admin, type));
        }
        menu.button(
                17,
                MenuIcons.icon(Material.ARROW, msg().get("quest.dialog.back")),
                e -> editor.list(admin, 0));
        menu.open(admin);
    }

    private void askId(Player admin, QuestType type) {
        editor.input(
                admin,
                text("new.id_title"),
                List.of(text("new.id_body", Placeholder.unparsed("type", type.key()))),
                "",
                40,
                false,
                raw -> {
                    String name = raw.toLowerCase(java.util.Locale.ROOT);
                    if (!NAME.matcher(name).matches()) {
                        msg().send(admin, "quest.editor.new.bad_id");
                        askId(admin, type);
                        return;
                    }
                    String id = "quest/" + type.key() + "/" + name;
                    var _ =
                            store.exists(id)
                                    .thenAccept(
                                            exists -> {
                                                if (exists) {
                                                    msg().send(
                                                                    admin,
                                                                    "quest.editor.new.exists",
                                                                    Placeholder.unparsed("id", id));
                                                    askId(admin, type);
                                                    return;
                                                }
                                                askName(admin, type, id);
                                            });
                });
    }

    private void askName(Player admin, QuestType type, String id) {
        editor.input(
                admin,
                text("new.name_title"),
                List.of(text("new.name_body")),
                "",
                40,
                false,
                name -> {
                    if (name.isBlank()) {
                        askName(admin, type, id);
                        return;
                    }
                    QuestDraft draft = QuestDraft.create(id, name, type);
                    editor.edit(admin, draft);
                });
    }
}
