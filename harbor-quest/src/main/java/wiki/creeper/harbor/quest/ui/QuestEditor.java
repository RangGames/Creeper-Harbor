package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.npc.api.NpcInfo;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.editor.EditorStore;
import wiki.creeper.harbor.quest.editor.QuestDraft;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * In-game quest editor (/harbor quest editor, proposed). Creates and edits quests through a chest
 * GUI without knowing YAML. Saving rewrites only that entry in
 * plugins/Harbor/content/quests/&lt;file&gt;.yml and reloads the quest type. If the content check
 * fails, the file is restored and the errors are shown (existing quests keep running).
 *
 * <ul>
 *   <li>List: all quests + new quest (type → ID → name) + resume editing
 *   <li>Edit: name and description (input screen), start and turn-in NPC (list / nearest NPC),
 *       requirements, accept offer style, objectives (add, edit, order), rewards (adjust by
 *       clicking, add the Harbor item held in hand), preview, save, revert, delete
 * </ul>
 *
 * The draft being edited stays in memory per admin, so editing can resume after closing the window
 * (lost when the server stops). Every save and delete is recorded in admin_log.
 */
final class QuestEditor {

    private static final List<String> OFFER_STYLES =
            List.of("", "dialog", "hologram", "title", "chat", "actionbar");

    private final HarborApi api;
    private final QuestServices q;
    private final EditorStore store;
    private final EditorPickers pickers;
    private final ObjectiveEditor objectives;
    private final RequirementsEditor requirements;
    private final EditorRewards rewards;
    private final NewQuestFlow newQuest;
    private final AdminLog adminLog;
    private final Map<UUID, QuestDraft> drafts = new HashMap<>();

    QuestEditor(HarborApi api, QuestServices q, EditorStore store, AdminLog adminLog) {
        this.api = api;
        this.q = q;
        this.store = store;
        this.pickers = new EditorPickers(api);
        this.adminLog = adminLog;
        this.objectives = new ObjectiveEditor(api, q, pickers, this);
        this.requirements = new RequirementsEditor(api, pickers, this);
        this.rewards = new EditorRewards(api, this);
        this.newQuest = new NewQuestFlow(api, store, this);
    }

    MessageService msg() {
        return api.messages();
    }

    Component text(String key, TagResolver... resolvers) {
        return msg().get("quest.editor." + key, resolvers);
    }

    // ------------------------------------------------------------------ List

    void open(Player admin) {
        list(admin, 0);
    }

    /** /harbor quest editor <quest>: edit directly. */
    void open(Player admin, String questId) {
        load(admin, questId);
    }

    void list(Player admin, int page) {
        List<QuestDef> all =
                q.engine().defs().stream()
                        .sorted(Comparator.comparing(d -> d.id().id().toString()))
                        .toList();
        int perPage = 45;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu = new Menu(text("title.list"), 6);
        int start = shown * perPage;
        for (int i = 0; i < perPage && start + i < all.size(); i++) {
            QuestDef def = all.get(start + i);
            menu.button(
                    i,
                    MenuIcons.icon(
                            def.main() ? Material.NETHER_STAR : Material.BOOK,
                            text("list.quest", Placeholder.unparsed("name", def.name())),
                            List.of(
                                    text(
                                            "list.id",
                                            Placeholder.unparsed("id", def.id().toString())),
                                    text("list.hint")),
                            false),
                    e -> load(admin, def.id().id().toString()));
        }
        for (int i = 45; i < 54; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        menu.button(
                45,
                MenuIcons.icon(
                        Material.LIME_DYE, text("list.new"), List.of(text("list.new_hint")), true),
                e -> newQuest.chooseType(admin));
        QuestDraft draft = drafts.get(admin.getUniqueId());
        if (draft != null) {
            menu.button(
                    46,
                    MenuIcons.icon(
                            Material.WRITABLE_BOOK,
                            text("list.resume", Placeholder.unparsed("id", draft.id())),
                            List.of(
                                    text(
                                            draft.dirty()
                                                    ? "list.resume_dirty"
                                                    : "list.resume_clean")),
                            draft.dirty()),
                    e -> main(admin, draft));
        }
        if (shown > 0) {
            menu.button(
                    48,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.previous")),
                    e -> list(admin, shown - 1));
        }
        if (shown < pages - 1) {
            menu.button(
                    50,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.next")),
                    e -> list(admin, shown + 1));
        }
        menu.button(
                53,
                MenuIcons.icon(Material.BARRIER, msg().get("quest.book.close")),
                e -> admin.closeInventory());
        menu.open(admin);
    }

    private void load(Player admin, String questId) {
        msg().send(admin, "quest.editor.loading");
        var _ =
                store.find(questId)
                        .thenAccept(
                                found -> {
                                    if (!admin.isOnline()) {
                                        return;
                                    }
                                    if (found.isEmpty()) {
                                        msg().send(
                                                        admin,
                                                        "quest.editor.not_found",
                                                        Placeholder.unparsed("id", questId));
                                        return;
                                    }
                                    QuestDraft draft =
                                            QuestDraft.of(found.get().file(), found.get().entry());
                                    drafts.put(admin.getUniqueId(), draft);
                                    main(admin, draft);
                                });
    }

    /** Starts editing a draft: remembers it for the admin and opens the edit screen. */
    void edit(Player admin, QuestDraft draft) {
        drafts.put(admin.getUniqueId(), draft);
        main(admin, draft);
    }

    // ------------------------------------------------------------------ Edit screen

    void main(Player admin, QuestDraft d) {
        Menu menu =
                new Menu(
                        text(
                                d.dirty() ? "title.edit_dirty" : "title.edit",
                                Placeholder.unparsed("name", d.string("name", "?"))),
                        6);
        // row 0: basic info
        menu.button(
                0,
                MenuIcons.icon(
                        Material.NAME_TAG,
                        text("field.name", Placeholder.unparsed("value", d.string("name", ""))),
                        List.of(text("hint.input")),
                        false),
                e ->
                        input(
                                admin,
                                text("field.name_title"),
                                List.of(),
                                d.string("name", ""),
                                40,
                                false,
                                v -> {
                                    d.set("name", v);
                                    main(admin, d);
                                }));
        List<Component> desc = new ArrayList<>();
        d.description().forEach(l -> desc.add(text("field.line", Placeholder.unparsed("text", l))));
        desc.add(text("hint.input_lines"));
        menu.button(
                1,
                MenuIcons.icon(Material.WRITABLE_BOOK, text("field.description"), desc, false),
                e ->
                        input(
                                admin,
                                text("field.description_title"),
                                List.of(text("field.description_body")),
                                String.join("\n", d.description()),
                                1000,
                                true,
                                v -> {
                                    d.description(v);
                                    main(admin, d);
                                }));
        npcField(menu, 2, admin, d, "start_npc", Material.VILLAGER_SPAWN_EGG);
        npcField(menu, 3, admin, d, "end_npc", Material.EMERALD);
        menu.button(
                4,
                MenuIcons.icon(
                        Material.IRON_BARS,
                        text("field.requires"),
                        requirements.requiresLore(d),
                        false),
                e -> requirements.requires(admin, d));
        String style = d.string("offer_style", "");
        menu.button(
                5,
                MenuIcons.icon(
                        Material.PAINTING,
                        text(
                                "field.offer_style",
                                Placeholder.component(
                                        "value",
                                        style.isEmpty()
                                                ? text("field.offer_style_default")
                                                : Component.text(style))),
                        List.of(text("hint.cycle")),
                        false),
                e -> {
                    int i = OFFER_STYLES.indexOf(style);
                    int next =
                            Math.floorMod(
                                    i + (e.getClick().isRightClick() ? -1 : 1),
                                    OFFER_STYLES.size());
                    d.set("offer_style", OFFER_STYLES.get(next));
                    main(admin, d);
                });
        menu.button(6, MenuIcons.filler(), null);
        menu.button(7, MenuIcons.filler(), null);
        menu.button(
                8,
                MenuIcons.icon(
                        Material.OAK_SIGN,
                        text("field.info"),
                        List.of(
                                text("field.info_id", Placeholder.unparsed("id", d.id())),
                                text(
                                        "field.info_file",
                                        Placeholder.unparsed("file", "quests/" + d.file())),
                                text(
                                        "field.info_type",
                                        Placeholder.unparsed("type", d.type().key())),
                                text(d.created() ? "field.info_new" : "field.info_existing")),
                        false),
                null);
        // rows 1~3: objectives
        List<Map<String, Object>> list = d.objectives();
        int slot = 9;
        for (int i = 0; i < list.size() && slot < 35; i++, slot++) {
            int index = i;
            menu.button(
                    slot,
                    objectives.icon(admin, d, list.get(i), i),
                    e -> objectives.open(admin, d, index));
        }
        if (slot < 36) {
            menu.button(
                    slot,
                    MenuIcons.icon(
                            Material.LIME_DYE,
                            text("objective.add"),
                            List.of(text("objective.add_hint")),
                            true),
                    e -> objectives.chooseType(admin, d, -1));
        }
        // row 4: rewards
        rewards.number(menu, 36, admin, d, Material.GOLD_INGOT, "gold", 100, 1000);
        rewards.number(menu, 37, admin, d, Material.EXPERIENCE_BOTTLE, "exp", 10, 100);
        rewards.number(menu, 38, admin, d, Material.PRISMARINE_CRYSTALS, "mulbineul", 1, 5);
        rewards.number(menu, 39, admin, d, Material.BELL, "reputation", 10, 50);
        menu.button(
                40,
                MenuIcons.icon(Material.CHEST, text("reward.items"), rewards.itemsLore(d), false),
                e -> {
                    if (e.getClick().isRightClick()) {
                        List<Object> items = d.list("rewards", "items");
                        if (!items.isEmpty()) {
                            items.removeLast();
                            d.touch();
                        }
                    } else {
                        rewards.addHeldItem(admin, d);
                    }
                    main(admin, d);
                });
        for (int i = 41; i < 45; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        // row 5: save etc.
        for (int i = 45; i < 54; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        menu.button(45, MenuIcons.icon(Material.ARROW, text("action.list")), e -> list(admin, 0));
        menu.button(
                47,
                MenuIcons.icon(
                        Material.PAPER,
                        text("action.preview"),
                        List.of(text("action.preview_hint")),
                        false),
                e -> preview(admin, d));
        menu.button(
                49,
                MenuIcons.icon(
                        Material.LIME_CONCRETE,
                        text("action.save"),
                        List.of(
                                text(
                                        "action.save_hint",
                                        Placeholder.unparsed("file", "quests/" + d.file()))),
                        d.dirty()),
                e -> save(admin, d));
        menu.button(
                51,
                MenuIcons.icon(
                        Material.CLOCK,
                        text("action.revert"),
                        List.of(text("action.revert_hint")),
                        false),
                e -> {
                    if (d.created()) {
                        drafts.remove(admin.getUniqueId());
                        list(admin, 0);
                    } else {
                        load(admin, d.id());
                    }
                });
        if (!d.created()) {
            menu.button(
                    53,
                    MenuIcons.icon(
                            Material.TNT,
                            text("action.delete"),
                            List.of(text("action.delete_hint")),
                            false),
                    e -> confirmDelete(admin, d));
        }
        menu.open(admin);
    }

    private void npcField(
            Menu menu, int slot, Player admin, QuestDraft d, String key, Material icon) {
        String value = d.string(key, "");
        menu.button(
                slot,
                MenuIcons.icon(
                        icon,
                        text(
                                "field." + key,
                                Placeholder.unparsed("value", value.isEmpty() ? "-" : value)),
                        List.of(text("hint.npc")),
                        false),
                e -> {
                    ClickType click = e.getClick();
                    if (click.isShiftClick()) {
                        d.set(key, null);
                        main(admin, d);
                    } else if (click.isRightClick()) {
                        Optional<NpcInfo> near = q.npcs().nearest(admin, 10);
                        if (near.isEmpty()) {
                            msg().send(admin, "quest.editor.no_npc_near");
                        } else {
                            d.set(key, near.get().id().id().toString());
                        }
                        main(admin, d);
                    } else {
                        pickers.pick(
                                admin,
                                text("title.pick_npc"),
                                pickers.npcs(),
                                v -> {
                                    d.set(key, v);
                                    main(admin, d);
                                },
                                () -> main(admin, d));
                    }
                });
    }

    /** Left +small · right -small · Shift+left +big · Shift+right -big. */
    static int step(@Nullable ClickType click, int small, int big) {
        if (click == null) {
            return 0;
        }
        int amount = click.isShiftClick() ? big : small;
        return click.isRightClick() ? -amount : amount;
    }

    // ------------------------------------------------------------------ Saving

    private void preview(Player admin, QuestDraft d) {
        admin.closeInventory();
        msg().send(
                        admin,
                        "quest.editor.preview_header",
                        Placeholder.unparsed("file", "quests/" + d.file()));
        for (String line : EditorStore.preview(d.entry()).lines().toList()) {
            msg().send(admin, "quest.editor.preview_line", Placeholder.unparsed("line", line));
        }
        msg().send(admin, "quest.editor.preview_footer");
    }

    private void save(Player admin, QuestDraft d) {
        admin.closeInventory();
        msg().send(admin, "quest.editor.saving");
        var _ =
                store.save(d.file(), d.id(), d.entry())
                        .thenAccept(
                                outcome -> {
                                    if (!admin.isOnline()) {
                                        return;
                                    }
                                    if (outcome.ok()) {
                                        d.saved();
                                        adminLog.log(
                                                admin, "editor.save", d.id(), "quests/" + d.file());
                                        msg().send(
                                                        admin,
                                                        "quest.editor.saved",
                                                        Placeholder.unparsed("id", d.id()),
                                                        Placeholder.unparsed(
                                                                "file", "quests/" + d.file()));
                                        // a new quest is also reloaded as an existing entry after
                                        // saving
                                        load(admin, d.id());
                                    } else {
                                        showErrors(admin, outcome.errors());
                                    }
                                });
    }

    private void showErrors(Player admin, String errors) {
        msg().send(admin, "quest.editor.save_failed");
        List<String> lines = errors.lines().toList();
        for (String line : lines.subList(0, Math.min(10, lines.size()))) {
            msg().send(admin, "quest.editor.error_line", Placeholder.unparsed("line", line));
        }
        if (lines.size() > 10) {
            msg().send(
                            admin,
                            "quest.editor.error_more",
                            Placeholder.unparsed("count", String.valueOf(lines.size() - 10)));
        }
        msg().send(admin, "quest.editor.reopen_hint");
    }

    private void confirmDelete(Player admin, QuestDraft d) {
        api.dialogs()
                .confirm(
                        admin,
                        text("action.delete"),
                        List.of(text("delete.body", Placeholder.unparsed("id", d.id()))),
                        text("delete.yes"),
                        text("delete.no"),
                        ok -> {
                            if (!ok) {
                                main(admin, d);
                                return;
                            }
                            var _ =
                                    store.delete(d.file(), d.id())
                                            .thenAccept(
                                                    outcome -> {
                                                        if (outcome.ok()) {
                                                            drafts.remove(admin.getUniqueId());
                                                            adminLog.log(
                                                                    admin,
                                                                    "editor.delete",
                                                                    d.id(),
                                                                    "quests/" + d.file());
                                                            msg().send(
                                                                            admin,
                                                                            "quest.editor.deleted",
                                                                            Placeholder.unparsed(
                                                                                    "id", d.id()));
                                                        } else {
                                                            showErrors(admin, outcome.errors());
                                                        }
                                                    });
                        });
    }

    // ------------------------------------------------------------------ Helpers

    void input(
            Player admin,
            Component title,
            List<Component> body,
            String initial,
            int max,
            boolean multiline,
            Consumer<String> result) {
        admin.closeInventory();
        api.dialogs()
                .input(
                        admin,
                        title,
                        body,
                        text("input.label"),
                        initial,
                        max,
                        multiline,
                        text("input.ok"),
                        text("input.cancel"),
                        result);
    }

    static Material typeIcon(QuestType type) {
        return switch (type) {
            case MAIN -> Material.NETHER_STAR;
            case REGION -> Material.FILLED_MAP;
            case JOB -> Material.FISHING_ROD;
            case DAILY -> Material.PAPER;
            case WEEKLY -> Material.MAP;
            case COLLECT -> Material.TROPICAL_FISH_BUCKET;
            case HIDDEN -> Material.ECHO_SHARD;
            case EVENT -> Material.FIREWORK_ROCKET;
        };
    }
}
