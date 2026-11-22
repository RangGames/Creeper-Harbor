package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;
import wiki.creeper.harbor.quest.logic.TrackerLine;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * Quest book (chest GUI, proposed). Opened with /퀘스트. The top row has tabs (in progress · available
 * · completed) and shortcuts (board · chronicle · tracker display · close), the middle 4 rows quest
 * icons, and the bottom row page navigation. Icon lore includes the objective progress bar, rewards
 * and controls.
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
    private static final int FIRST = 9;
    private static final int PER_PAGE = 36;
    private static final int NAV = 45;

    private final HarborApi api;
    private final QuestServices q;
    private final QuestDialogs dialogs;

    public QuestBook(HarborApi api, QuestServices q, QuestDialogs dialogs) {
        this.api = api;
        this.q = q;
        this.dialogs = dialogs;
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
        tabs(menu, player, tab);
        int start = shownPage * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < quests.size(); i++) {
            QuestDef def = quests.get(start + i);
            menu.button(
                    FIRST + i,
                    icon(player, hp, def, tab),
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

    private void tabs(Menu menu, Player player, Tab current) {
        Balance b = balance();
        tab(
                menu,
                0,
                player,
                Tab.ACTIVE,
                current,
                MenuIcons.material(b, "tab_active", Material.BOOK));
        tab(
                menu,
                1,
                player,
                Tab.AVAILABLE,
                current,
                MenuIcons.material(b, "tab_available", Material.WRITABLE_BOOK));
        tab(
                menu,
                2,
                player,
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
        boolean barOn =
                !api.flags()
                        .hasPlayer(
                                player.getUniqueId(),
                                wiki.creeper.harbor.quest.service.TrackerBar.OFF_FLAG);
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

    private void tab(Menu menu, int slot, Player player, Tab tab, Tab current, Material icon) {
        menu.button(
                slot,
                MenuIcons.icon(
                        icon,
                        msg().get(
                                        tab == current
                                                ? "quest.book.tab_selected"
                                                : "quest.book.tab_other",
                                        Placeholder.component(
                                                "tab", msg().get("quest.book.tab." + key(tab)))),
                        List.of(msg().get("quest.book.tab_hint." + key(tab))),
                        tab == current),
                tab == current ? null : e -> open(player, tab, 0));
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

    // ------------------------------------------------------------------ Icons

    private ItemStack icon(Player player, HarborPlayer hp, QuestDef def, Tab tab) {
        Optional<QuestEntry> entry = hp.get(QuestEngine.COMPONENT).entry(def.id());
        QuestState state = engine().avail(hp, def).state();
        QuestId tracked = hp.get(QuestEngine.COMPONENT).tracked();
        boolean isTracked = tab == Tab.ACTIVE && def.id().equals(tracked);
        List<Component> lore = new ArrayList<>();
        lore.add(
                msg().get(
                                "quest.book.type",
                                Placeholder.component(
                                        "type", msg().get("quest.type." + def.type().key()))));
        for (String line : def.description()) {
            lore.add(msg().get("quest.book.description", Placeholder.unparsed("text", line)));
        }
        lore.add(Component.empty());
        lore.add(msg().get("quest.book.objectives"));
        Optional<Progress> progress = entry.map(QuestEntry::progress);
        List<Objective> current = progress.map(p -> QuestMachine.current(def, p)).orElse(List.of());
        for (Objective o : def.objectives()) {
            lore.add(objectiveLine(player, def, o, progress, tab == Tab.COMPLETED, current));
        }
        Progress.Snapshot snap = progress.map(Progress::snap).orElseGet(() -> q.preview(hp, def));
        lore.add(Component.empty());
        lore.add(
                msg().get(
                                "quest.book.rewards",
                                Placeholder.unparsed("money", String.valueOf(snap.gold())),
                                Placeholder.unparsed("mulbineul", String.valueOf(snap.mulbineul())),
                                Placeholder.unparsed("exp", String.valueOf(snap.exp())),
                                Placeholder.unparsed(
                                        "reputation", String.valueOf(snap.reputation()))));
        if (!def.rewards().items().isEmpty()
                || def.rewards().title() != null
                || def.rewards().chronicle() != null) {
            lore.add(msg().get("quest.dialog.rewards_extra"));
        }
        lore.add(Component.empty());
        lore.addAll(hints(player, def, tab, state, isTracked));
        String nameKey =
                switch (state) {
                    case READY -> "quest.book.name_ready";
                    case COMPLETED -> "quest.book.name_completed";
                    default -> def.main() ? "quest.book.name_main" : "quest.book.name";
                };
        return MenuIcons.icon(
                material(def, state),
                msg().get(nameKey, Placeholder.unparsed("name", def.name())),
                lore,
                isTracked || state == QuestState.READY);
    }

    private Component objectiveLine(
            Player player,
            QuestDef def,
            Objective o,
            Optional<Progress> progress,
            boolean completed,
            List<Objective> current) {
        int value = completed ? o.count() : progress.map(p -> p.count(o.key())).orElse(0);
        boolean done = completed || (o.tracked() && value >= o.count());
        boolean now = !completed && current.contains(o);
        String key =
                done
                        ? "quest.book.objective_done"
                        : now ? "quest.book.objective_now" : "quest.book.objective";
        return msg().get(
                        key,
                        Placeholder.component(
                                "bar", bar(o.tracked() || completed ? value : 0, o.count())),
                        Placeholder.unparsed("text", engine().objectiveText(player, def, o)),
                        Placeholder.unparsed(
                                "value", o.tracked() || completed ? String.valueOf(value) : "-"),
                        Placeholder.unparsed("count", String.valueOf(o.count())));
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
            Player player, QuestDef def, Tab tab, QuestState state, boolean tracked) {
        List<Component> lines = new ArrayList<>();
        switch (tab) {
            case ACTIVE -> {
                if (tracked) {
                    lines.add(msg().get("quest.book.hint.tracked"));
                } else {
                    lines.add(msg().get("quest.book.hint.track"));
                }
                if (state == QuestState.READY) {
                    Optional<NpcId> npc = def.turnInNpc();
                    lines.add(
                            npc.isPresent()
                                    ? msg().get(
                                                    "quest.book.hint.report_to",
                                                    Placeholder.component(
                                                            "npc", q.npcName(player, npc.get())))
                                    : msg().get("quest.book.hint.report"));
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
