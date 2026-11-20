package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * 플레이어 퀘스트 관리 GUI (/harbor quest gui &lt;플레이어&gt;, 추가 제안). 명령(info·set·progress·complete·reset)과 같은
 * 엔진 호출·권한·admin_log를 쓰고, 클릭 한 번으로 상태를 바꾼다.
 *
 * <ul>
 *   <li>목록: 필터(진행 중 · 완료 · 전체), 아이콘 색이 상태
 *   <li>퀘스트 화면: 진행 중으로 · 보고 가능으로 · 완료(보상) · 완료(보상 없음) · 초기화, 목표별 좌클릭 +1 · 우클릭 -1 · Shift+좌클릭 채움 ·
 *       Shift+우클릭 0
 * </ul>
 */
final class QuestAdminMenu {

    /** 목록 필터. */
    enum Filter {
        OPEN,
        COMPLETED,
        ALL
    }

    private static final int PER_PAGE = 36;

    private final HarborApi api;
    private final QuestServices q;
    private final AdminLog adminLog;

    QuestAdminMenu(HarborApi api, QuestServices q, AdminLog adminLog) {
        this.api = api;
        this.q = q;
        this.adminLog = adminLog;
    }

    private MessageService msg() {
        return api.messages();
    }

    private QuestEngine engine() {
        return q.engine();
    }

    private static boolean can(Player admin, String perm) {
        return admin.hasPermission("harbor.admin.quest." + perm);
    }

    // ------------------------------------------------------------------ 목록

    void open(Player admin, Player target) {
        list(admin, target, Filter.OPEN, 0);
    }

    private void list(Player admin, Player target, Filter filter, int page) {
        if (!target.isOnline()) {
            msg().send(admin, "quest.admin.gui.offline");
            admin.closeInventory();
            return;
        }
        HarborPlayer hp = api.players().get(target);
        List<QuestDef> quests = new ArrayList<>();
        for (QuestDef def : engine().defs()) {
            QuestState state = engine().avail(hp, def).state();
            boolean keep =
                    switch (filter) {
                        case OPEN -> state.open();
                        case COMPLETED -> state == QuestState.COMPLETED;
                        case ALL -> true;
                    };
            if (keep) {
                quests.add(def);
            }
        }
        quests.sort(
                Comparator.comparing((QuestDef d) -> !d.main())
                        .thenComparing(d -> d.id().id().toString()));
        int pages = Math.max(1, (quests.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu =
                new Menu(
                        msg().get(
                                        "quest.admin.gui.title",
                                        Placeholder.unparsed("player", target.getName())),
                        6);
        int slot = 0;
        for (Filter f : Filter.values()) {
            String key = f.name().toLowerCase(Locale.ROOT);
            menu.button(
                    slot++,
                    MenuIcons.icon(
                            f == filter ? Material.LIME_DYE : Material.GRAY_DYE,
                            msg().get("quest.admin.gui.filter." + key)),
                    f == filter ? null : e -> list(admin, target, f, 0));
        }
        for (int i = 3; i < 9; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < quests.size(); i++) {
            QuestDef def = quests.get(start + i);
            QuestState state = engine().avail(hp, def).state();
            menu.button(
                    9 + i,
                    MenuIcons.icon(
                            stateIcon(state),
                            msg().get(
                                            "quest.admin.gui.quest",
                                            Placeholder.unparsed("name", def.name()),
                                            Placeholder.unparsed("state", state.key())),
                            List.of(
                                    msg().get(
                                                    "quest.admin.gui.id",
                                                    Placeholder.unparsed(
                                                            "id", def.id().toString())),
                                    msg().get("quest.admin.gui.open_hint")),
                            state.open()),
                    e -> quest(admin, target, def, () -> list(admin, target, filter, shown)));
        }
        for (int i = 45; i < 54; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        if (shown > 0) {
            menu.button(
                    45,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.previous")),
                    e -> list(admin, target, filter, shown - 1));
        }
        if (shown < pages - 1) {
            menu.button(
                    53,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.next")),
                    e -> list(admin, target, filter, shown + 1));
        }
        menu.open(admin);
    }

    private static Material stateIcon(QuestState state) {
        return switch (state) {
            case ACTIVE -> Material.YELLOW_CONCRETE;
            case READY -> Material.LIME_CONCRETE;
            case COMPLETING -> Material.LIGHT_BLUE_CONCRETE;
            case COMPLETED -> Material.GREEN_CONCRETE;
            case FAILED, ABANDONED -> Material.RED_CONCRETE;
            case AVAILABLE -> Material.WHITE_CONCRETE;
            default -> Material.GRAY_CONCRETE;
        };
    }

    // ------------------------------------------------------------------ 퀘스트 하나

    private void quest(Player admin, Player target, QuestDef def, Runnable back) {
        if (!target.isOnline()) {
            msg().send(admin, "quest.admin.gui.offline");
            admin.closeInventory();
            return;
        }
        HarborPlayer hp = api.players().get(target);
        QuestState state = engine().avail(hp, def).state();
        Optional<QuestEntry> entry = hp.get(QuestEngine.COMPONENT).entry(def.id());
        Menu menu =
                new Menu(
                        msg().get(
                                        "quest.admin.gui.quest_title",
                                        Placeholder.unparsed("name", def.name()),
                                        Placeholder.unparsed("player", target.getName())),
                        4);
        // 상태 변경은 메모리에 바로 반영되므로 곧바로 다시 그린다 (보상 완료는 처리 중으로 보인다)
        Runnable reopen = () -> quest(admin, target, def, back);
        action(
                menu,
                0,
                admin,
                "set",
                Material.YELLOW_CONCRETE,
                "active",
                () -> {
                    var _ = engine().adminSet(target, def, QuestState.ACTIVE);
                    adminLog.log(
                            admin, "set", target.getUniqueId().toString(), def.id() + " active");
                    reopen.run();
                });
        action(
                menu,
                1,
                admin,
                "set",
                Material.LIME_CONCRETE,
                "ready",
                () -> {
                    var _ = engine().adminSet(target, def, QuestState.READY);
                    adminLog.log(
                            admin, "set", target.getUniqueId().toString(), def.id() + " ready");
                    reopen.run();
                });
        action(
                menu,
                2,
                admin,
                "complete",
                Material.GOLD_BLOCK,
                "complete_reward",
                () -> {
                    complete(target, def, entry, true);
                    adminLog.log(
                            admin,
                            "complete",
                            target.getUniqueId().toString(),
                            def.id().toString());
                    reopen.run();
                });
        action(
                menu,
                3,
                admin,
                "complete",
                Material.GREEN_CONCRETE,
                "complete_plain",
                () -> {
                    complete(target, def, entry, false);
                    adminLog.log(
                            admin,
                            "complete",
                            target.getUniqueId().toString(),
                            def.id() + " --no-reward");
                    reopen.run();
                });
        action(
                menu,
                4,
                admin,
                "set",
                Material.RED_CONCRETE,
                "failed",
                () -> {
                    var _ = engine().adminSet(target, def, QuestState.FAILED);
                    adminLog.log(
                            admin, "set", target.getUniqueId().toString(), def.id() + " failed");
                    reopen.run();
                });
        action(
                menu,
                6,
                admin,
                "set",
                Material.TNT,
                "reset",
                () -> {
                    var _ = engine().adminReset(target, def.id());
                    adminLog.log(
                            admin, "reset", target.getUniqueId().toString(), def.id().toString());
                    reopen.run();
                });
        menu.button(
                8,
                MenuIcons.icon(
                        Material.OAK_SIGN,
                        msg().get(
                                        "quest.admin.gui.state",
                                        Placeholder.unparsed("state", state.key())),
                        List.of(
                                msg().get(
                                                "quest.admin.gui.id",
                                                Placeholder.unparsed("id", def.id().toString()))),
                        false),
                null);
        int slot = 9;
        for (Objective o : def.objectives()) {
            if (slot >= 27) {
                break;
            }
            int value = entry.map(e -> e.progress().count(o.key())).orElse(0);
            boolean editable = state.open() && o.tracked() && can(admin, "set");
            menu.button(
                    slot++,
                    MenuIcons.icon(
                            value >= o.count() ? Material.LIME_DYE : Material.CLOCK,
                            msg().get(
                                            "quest.admin.gui.objective",
                                            Placeholder.unparsed("key", o.key()),
                                            Placeholder.unparsed(
                                                    "text", engine().objectiveText(admin, def, o)),
                                            Placeholder.unparsed("value", String.valueOf(value)),
                                            Placeholder.unparsed(
                                                    "count", String.valueOf(o.count()))),
                            List.of(
                                    msg().get(
                                                    editable
                                                            ? "quest.admin.gui.objective_hint"
                                                            : "quest.admin.gui.objective_locked")),
                            false),
                    editable
                            ? e -> {
                                int next = nextValue(value, o.count(), e.getClick());
                                if (engine().adminProgress(target, def, o.key(), next)) {
                                    adminLog.log(
                                            admin,
                                            "progress",
                                            target.getUniqueId().toString(),
                                            def.id() + " " + o.key() + "=" + next);
                                }
                                quest(admin, target, def, back);
                            }
                            : null);
        }
        for (int i = 27; i < 36; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        menu.button(
                27,
                MenuIcons.icon(Material.ARROW, msg().get("quest.dialog.back")),
                e -> back.run());
        menu.open(admin);
    }

    /** 좌 +1 · 우 -1 · Shift+좌 채움 · Shift+우 0. */
    static int nextValue(int value, int count, @Nullable ClickType click) {
        if (click == null) {
            return value;
        }
        if (click.isShiftClick()) {
            return click.isLeftClick() ? count : 0;
        }
        return Math.clamp(value + (click.isRightClick() ? -1 : 1), 0, Math.max(count, 0));
    }

    private void complete(Player target, QuestDef def, Optional<QuestEntry> entry, boolean reward) {
        if (entry.isEmpty() || !entry.get().state().open()) {
            var _ = engine().adminSet(target, def, QuestState.COMPLETED);
        } else {
            var _ = q.forceComplete(target, def, reward);
        }
    }

    private void action(
            Menu menu,
            int slot,
            Player admin,
            String perm,
            Material icon,
            String key,
            Runnable run) {
        boolean allowed = can(admin, perm);
        menu.button(
                slot,
                MenuIcons.icon(
                        allowed ? icon : Material.BARRIER,
                        msg().get("quest.admin.gui.action." + key),
                        List.of(
                                msg().get(
                                                allowed
                                                        ? "quest.admin.gui.action_hint." + key
                                                        : "quest.admin.gui.no_permission")),
                        false),
                allowed ? e -> run.run() : null);
    }
}
