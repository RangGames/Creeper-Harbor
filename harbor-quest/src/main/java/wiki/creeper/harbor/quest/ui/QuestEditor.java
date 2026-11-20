package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.npc.api.NpcInfo;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.editor.EditorStore;
import wiki.creeper.harbor.quest.editor.QuestDraft;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * 게임 안 퀘스트 편집기 (/harbor quest editor, 추가 제안). YAML을 몰라도 상자 GUI로 퀘스트를 만들고 고친다. 저장하면
 * plugins/Harbor/content/quests/&lt;파일&gt;.yml의 그 항목만 바꿔 쓰고 quest 형식을 리로드한다. 콘텐츠 검사에 걸리면 파일을 되돌리고
 * 오류를 보여준다 (기존 퀘스트는 그대로 돌아간다).
 *
 * <ul>
 *   <li>목록: 모든 퀘스트 + 새 퀘스트(유형 → ID → 이름) + 이어서 편집
 *   <li>편집: 이름·설명(입력 화면), 시작·보고 NPC(목록 / 가장 가까운 NPC), 요구 조건, 수락 제안 방식, 목표(추가·편집·순서), 보상(클릭으로 증감, 손에
 *       든 Harbor 아이템 추가), 미리보기·저장·되돌리기·삭제
 * </ul>
 *
 * 편집 중인 초안은 관리자별로 메모리에 남아 창을 닫아도 이어서 고칠 수 있다 (서버를 끄면 사라진다). 모든 저장·삭제는 admin_log에 남는다.
 */
final class QuestEditor {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,40}");
    private static final List<String> OFFER_STYLES =
            List.of("", "dialog", "hologram", "title", "chat", "actionbar");

    private final HarborApi api;
    private final QuestServices q;
    private final EditorStore store;
    private final EditorPickers pickers;
    private final ObjectiveEditor objectives;
    private final AdminLog adminLog;
    private final Map<UUID, QuestDraft> drafts = new HashMap<>();

    QuestEditor(HarborApi api, QuestServices q, EditorStore store, AdminLog adminLog) {
        this.api = api;
        this.q = q;
        this.store = store;
        this.pickers = new EditorPickers(api);
        this.adminLog = adminLog;
        this.objectives = new ObjectiveEditor(api, q, pickers, this);
    }

    MessageService msg() {
        return api.messages();
    }

    Component text(String key, TagResolver... resolvers) {
        return msg().get("quest.editor." + key, resolvers);
    }

    // ------------------------------------------------------------------ 목록

    void open(Player admin) {
        list(admin, 0);
    }

    /** /harbor quest editor <퀘스트>: 바로 편집. */
    void open(Player admin, String questId) {
        load(admin, questId);
    }

    private void list(Player admin, int page) {
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
                e -> chooseType(admin));
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

    // ------------------------------------------------------------------ 새 퀘스트

    private void chooseType(Player admin) {
        Menu menu = new Menu(text("title.new_type"), 2);
        int slot = 0;
        for (QuestType type : QuestType.values()) {
            menu.button(
                    slot++,
                    MenuIcons.icon(
                            typeIcon(type),
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
                e -> list(admin, 0));
        menu.open(admin);
    }

    private void askId(Player admin, QuestType type) {
        input(
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
        input(
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
                    drafts.put(admin.getUniqueId(), draft);
                    main(admin, draft);
                });
    }

    // ------------------------------------------------------------------ 편집 화면

    void main(Player admin, QuestDraft d) {
        Menu menu =
                new Menu(
                        text(
                                d.dirty() ? "title.edit_dirty" : "title.edit",
                                Placeholder.unparsed("name", d.string("name", "?"))),
                        6);
        // 0줄: 기본 정보
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
                MenuIcons.icon(Material.IRON_BARS, text("field.requires"), requiresLore(d), false),
                e -> requires(admin, d));
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
        // 1~3줄: 목표
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
        // 4줄: 보상
        number(menu, 36, admin, d, Material.GOLD_INGOT, "gold", 100, 1000);
        number(menu, 37, admin, d, Material.EXPERIENCE_BOTTLE, "exp", 10, 100);
        number(menu, 38, admin, d, Material.PRISMARINE_CRYSTALS, "mulbineul", 1, 5);
        number(menu, 39, admin, d, Material.BELL, "reputation", 10, 50);
        menu.button(
                40,
                MenuIcons.icon(Material.CHEST, text("reward.items"), itemsLore(d), false),
                e -> {
                    if (e.getClick().isRightClick()) {
                        List<Object> items = d.list("rewards", "items");
                        if (!items.isEmpty()) {
                            items.removeLast();
                            d.touch();
                        }
                    } else {
                        addHeldItem(admin, d);
                    }
                    main(admin, d);
                });
        for (int i = 41; i < 45; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        // 5줄: 저장 등
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

    private void number(
            Menu menu,
            int slot,
            Player admin,
            QuestDraft d,
            Material icon,
            String key,
            int small,
            int big) {
        int value = QuestDraft.integer(d.section("rewards"), key, 0);
        menu.button(
                slot,
                MenuIcons.icon(
                        icon,
                        text("reward." + key, Placeholder.unparsed("value", String.valueOf(value))),
                        List.of(
                                text(
                                        "hint.number",
                                        Placeholder.unparsed("small", String.valueOf(small)),
                                        Placeholder.unparsed("big", String.valueOf(big)))),
                        false),
                e -> {
                    d.add("rewards", key, step(e.getClick(), small, big));
                    main(admin, d);
                });
    }

    /** 좌 +small · 우 -small · Shift+좌 +big · Shift+우 -big. */
    static int step(@Nullable ClickType click, int small, int big) {
        if (click == null) {
            return 0;
        }
        int amount = click.isShiftClick() ? big : small;
        return click.isRightClick() ? -amount : amount;
    }

    private List<Component> itemsLore(QuestDraft d) {
        List<Component> lore = new ArrayList<>();
        for (Object o : d.list("rewards", "items")) {
            if (o instanceof Map<?, ?> m) {
                lore.add(
                        text(
                                "reward.item_line",
                                Placeholder.unparsed("item", String.valueOf(m.get("item"))),
                                Placeholder.unparsed("amount", String.valueOf(m.get("amount")))));
            }
        }
        lore.add(text("hint.items"));
        return lore;
    }

    private void addHeldItem(Player admin, QuestDraft d) {
        ItemStack hand = admin.getInventory().getItemInMainHand();
        Optional<HarborItem> item = hand.isEmpty() ? Optional.empty() : api.items().read(hand);
        if (item.isEmpty() || !(item.get() instanceof HarborItem.Plain plain)) {
            msg().send(admin, "quest.editor.hold_item");
            return;
        }
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("item", plain.id().id().toString());
        entry.put("amount", String.valueOf(hand.getAmount()));
        d.list("rewards", "items").add(entry);
        d.touch();
    }

    // ------------------------------------------------------------------ 요구 조건

    private List<Component> requiresLore(QuestDraft d) {
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

    private void requires(Player admin, QuestDraft d) {
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
                e -> main(admin, d));
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
                    d.add("requires", key, step(e.getClick(), small, big));
                    requires(admin, d);
                });
    }

    // ------------------------------------------------------------------ 저장

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
                                        // 새 퀘스트도 저장 뒤에는 기존 항목으로 다시 읽는다
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

    // ------------------------------------------------------------------ 도우미

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
