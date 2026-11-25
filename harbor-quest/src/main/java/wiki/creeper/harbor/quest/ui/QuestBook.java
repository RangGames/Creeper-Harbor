package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * Quest book (chest GUI, proposed). Opened with /퀘스트. The top row has tabs with their quest counts
 * (in progress · available · completed) and shortcuts (board · chronicle · tracker display ·
 * close), the second row marks the selected tab, the middle 3 rows hold quest icons and the bottom
 * row page navigation. Icons come from {@link QuestBookIcons}.
 *
 * <ul>
 *   <li>In progress: left click track · right click details (Dialog) · Shift+right click abandon ·
 *       left click turns in a READY quest without a turn-in NPC
 *   <li>Available: left click shows the accept offer if there is no start NPC; otherwise tells
 *       which NPC
 * </ul>
 *
 * Main thread only. Redrawing creates a new Menu (the only state is tab and page).
 */
public final class QuestBook {

    /** Tab. */
    public enum Tab {
        ACTIVE,
        AVAILABLE,
        COMPLETED
    }

    private static final int ROWS = 6;

    /** Second row: a coloured pane under the selected tab. */
    private static final int MARK = 9;

    private static final int FIRST = 18;
    private static final int PER_PAGE = 27;
    private static final int NAV = 45;

    private final HarborApi api;
    private final QuestServices q;
    private final QuestDialogs dialogs;
    private final QuestBookIcons icons;

    public QuestBook(HarborApi api, QuestServices q, QuestDialogs dialogs) {
        this.api = api;
        this.q = q;
        this.dialogs = dialogs;
        this.icons = new QuestBookIcons(api, q);
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

    public void open(Player player) {
        open(player, Tab.ACTIVE, 0);
    }

    public void open(Player player, Tab tab, int page) {
        HarborPlayer hp = api.players().get(player);
        List<QuestDef> quests = quests(hp, tab);
        int pages = Math.max(1, (quests.size() + PER_PAGE - 1) / PER_PAGE);
        int shownPage = Math.clamp(page, 0, pages - 1);
        Menu menu =
                new Menu(
                        msg().get(
                                        "quest.book.title",
                                        Placeholder.component(
                                                "tab", msg().get("quest.book.tab." + key(tab)))),
                        ROWS);
        tabs(menu, player, hp, tab);
        for (int i = 0; i < 9; i++) {
            menu.button(
                    MARK + i,
                    MenuIcons.filler(
                            i == slot(tab)
                                    ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.GRAY_STAINED_GLASS_PANE),
                    null);
        }
        int start = shownPage * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < quests.size(); i++) {
            QuestDef def = quests.get(start + i);
            menu.button(
                    FIRST + i,
                    icons.icon(player, hp, def, tab),
                    e -> click(player, def, tab, shownPage, e.getClick()));
        }
        if (quests.isEmpty()) {
            menu.button(
                    FIRST + 13,
                    MenuIcons.icon(
                            Material.PAPER,
                            msg().get("quest.book.empty." + key(tab)),
                            List.of(msg().get("quest.book.empty_hint")),
                            false),
                    null);
        }
        for (int slot = NAV; slot < NAV + 9; slot++) {
            menu.button(slot, MenuIcons.filler(), null);
        }
        if (shownPage > 0) {
            menu.button(
                    NAV,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.previous")),
                    e -> open(player, tab, shownPage - 1));
        }
        menu.button(
                NAV + 4,
                MenuIcons.icon(
                        Material.MAP,
                        msg().get(
                                        "quest.book.page",
                                        Placeholder.unparsed("page", String.valueOf(shownPage + 1)),
                                        Placeholder.unparsed("pages", String.valueOf(pages)),
                                        Placeholder.unparsed(
                                                "count", String.valueOf(quests.size())))),
                null);
        if (shownPage < pages - 1) {
            menu.button(
                    NAV + 8,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.next")),
                    e -> open(player, tab, shownPage + 1));
        }
        menu.open(player);
    }

    // ------------------------------------------------------------------ Top row

    private void tabs(Menu menu, Player player, HarborPlayer hp, Tab current) {
        Balance b = balance();
        tab(
                menu,
                player,
                hp,
                Tab.ACTIVE,
                current,
                MenuIcons.material(b, "tab_active", Material.BOOK));
        tab(
                menu,
                player,
                hp,
                Tab.AVAILABLE,
                current,
                MenuIcons.material(b, "tab_available", Material.WRITABLE_BOOK));
        tab(
                menu,
                player,
                hp,
                Tab.COMPLETED,
                current,
                MenuIcons.material(b, "tab_completed", Material.ENCHANTED_BOOK));
        menu.button(3, MenuIcons.filler(), null);
        menu.button(
                4,
                MenuIcons.icon(
                        MenuIcons.material(b, "board", Material.OAK_HANGING_SIGN),
                        msg().get("quest.book.board"),
                        List.of(msg().get("quest.book.board_hint")),
                        false),
                e -> {
                    player.closeInventory();
                    dialogs.boardRoot(player);
                });
        menu.button(5, MenuIcons.filler(), null);
        menu.button(
                6,
                MenuIcons.icon(
                        MenuIcons.material(b, "chronicle", Material.FILLED_MAP),
                        msg().get("quest.book.chronicle"),
                        List.of(msg().get("quest.book.chronicle_hint")),
                        false),
                e -> {
                    player.closeInventory();
                    q.chronicle().open(player);
                });
        boolean barOn = q.trackerBar().alwaysOn(player.getUniqueId());
        menu.button(
                7,
                MenuIcons.icon(
                        MenuIcons.material(b, "tracker", Material.COMPASS),
                        msg().get(barOn ? "quest.book.tracker_on" : "quest.book.tracker_off"),
                        List.of(msg().get("quest.book.tracker_hint")),
                        barOn),
                e -> {
                    var _ =
                            q.trackerBar()
                                    .toggle(player)
                                    .thenAccept(
                                            on -> {
                                                if (player.isOnline()) {
                                                    open(player, current, 0);
                                                }
                                            });
                });
        menu.button(
                8,
                MenuIcons.icon(Material.BARRIER, msg().get("quest.book.close")),
                e -> player.closeInventory());
    }

    private void tab(
            Menu menu, Player player, HarborPlayer hp, Tab tab, Tab current, Material icon) {
        menu.button(
                slot(tab),
                MenuIcons.icon(
                        icon,
                        msg().get(
                                        tab == current
                                                ? "quest.book.tab_selected"
                                                : "quest.book.tab_other",
                                        Placeholder.component(
                                                "tab", msg().get("quest.book.tab." + key(tab))),
                                        Placeholder.unparsed(
                                                "count", String.valueOf(quests(hp, tab).size()))),
                        List.of(msg().get("quest.book.tab_hint." + key(tab))),
                        tab == current),
                tab == current ? null : e -> open(player, tab, 0));
    }

    /** Top-row slot of a tab (the marker row uses the same column). */
    private static int slot(Tab tab) {
        return switch (tab) {
            case ACTIVE -> 0;
            case AVAILABLE -> 1;
            case COMPLETED -> 2;
        };
    }

    private static String key(Tab tab) {
        return tab.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ List

    /**
     * Quests of the tab. In progress: main → ready to turn in → the rest; other tabs in ID order.
     * Board requests only while in progress.
     */
    List<QuestDef> quests(HarborPlayer hp, Tab tab) {
        List<QuestDef> found = new ArrayList<>();
        if (tab == Tab.ACTIVE) {
            for (Map.Entry<QuestDef, QuestEntry> e : q.open(hp)) {
                found.add(e.getKey());
            }
            found.sort(
                    Comparator.comparing((QuestDef d) -> !d.main())
                            .thenComparing(d -> engine().avail(hp, d).state() != QuestState.READY)
                            .thenComparing(d -> d.id().id().toString()));
            return found;
        }
        QuestState want = tab == Tab.AVAILABLE ? QuestState.AVAILABLE : QuestState.COMPLETED;
        for (QuestDef def : engine().defs()) {
            if (def.type().board()
                    || (def.type() == QuestType.HIDDEN && want == QuestState.AVAILABLE)) {
                continue;
            }
            if (engine().avail(hp, def).state() == want) {
                found.add(def);
            }
        }
        found.sort(
                Comparator.comparing((QuestDef d) -> !d.main())
                        .thenComparing(d -> d.id().id().toString()));
        return found;
    }

    // ------------------------------------------------------------------ Clicks

    private void click(Player player, QuestDef def, Tab tab, int page, @Nullable ClickType click) {
        HarborPlayer hp = api.players().get(player);
        QuestState state = engine().avail(hp, def).state();
        boolean right = click != null && click.isRightClick();
        boolean shift = click != null && click.isShiftClick();
        switch (tab) {
            case ACTIVE -> {
                if (right && shift) {
                    if (def.abandonable() && !def.main()) {
                        player.closeInventory();
                        dialogs.confirmAbandon(player, def);
                    }
                } else if (right) {
                    player.closeInventory();
                    dialogs.detail(player, def);
                } else if (state == QuestState.READY && def.turnInNpc().isEmpty()) {
                    player.closeInventory();
                    var _ = engine().turnIn(player, def.id());
                } else {
                    engine().track(player, def.id());
                    open(player, tab, page);
                }
            }
            case AVAILABLE -> {
                NpcId start = def.startNpc();
                if (start == null) {
                    player.closeInventory();
                    dialogs.offer(player, def);
                } else {
                    msg().send(
                                    player,
                                    "quest.book.go_to_npc",
                                    Placeholder.unparsed("name", def.name()),
                                    Placeholder.component("npc", q.npcName(player, start)));
                }
            }
            case COMPLETED -> {}
        }
    }
}
