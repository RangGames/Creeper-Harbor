package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.BoardState;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * 퀘스트 화면 (docs 20 6.4 퀘스트 목록): /퀘스트 목록 Dialog(진행 중·수락 가능·완료), 상세(추적·포기), NPC 대화의 quest:list, 수락 제안,
 * 게시판. 화면은 Core Dialog 래퍼만 쓴다.
 */
public final class QuestDialogs {

    private final HarborApi api;
    private final QuestServices q;

    public QuestDialogs(HarborApi api, QuestServices q) {
        this.api = api;
        this.q = q;
    }

    private MessageService msg() {
        return api.messages();
    }

    private QuestEngine engine() {
        return q.engine();
    }

    // ------------------------------------------------------------------ /퀘스트

    public void journal(Player player) {
        HarborPlayer hp = api.players().get(player);
        List<QuestDef> active = new ArrayList<>();
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (Map.Entry<QuestDef, QuestEntry> e : q.open(hp)) {
            active.add(e.getKey());
            choices.add(
                    new Dialogs.Choice(
                            label(e.getKey(), e.getValue().state()),
                            Component.text(e.getKey().type().key())));
        }
        int tabs = choices.size();
        choices.add(
                new Dialogs.Choice(msg().get("quest.journal.tab_available"), Component.empty()));
        choices.add(
                new Dialogs.Choice(msg().get("quest.journal.tab_completed"), Component.empty()));
        List<Component> body = new ArrayList<>();
        body.add(
                msg().get(
                                active.isEmpty() ? "quest.journal.empty" : "quest.journal.header",
                                Placeholder.unparsed("count", String.valueOf(active.size()))));
        api.dialogs()
                .choose(
                        player,
                        msg().get("quest.journal.title"),
                        body,
                        choices,
                        1,
                        index -> {
                            if (index < tabs) {
                                detail(player, active.get(index));
                            } else if (index == tabs) {
                                list(player, QuestState.AVAILABLE);
                            } else {
                                list(player, QuestState.COMPLETED);
                            }
                        });
    }

    /** 수락 가능·완료 목록 (게시판 의뢰 제외, 20개까지). */
    void list(Player player, QuestState state) {
        HarborPlayer hp = api.players().get(player);
        List<QuestDef> found = new ArrayList<>();
        for (QuestDef def : engine().defs()) {
            if (def.type().board()
                    || (def.type() == QuestType.HIDDEN && state == QuestState.AVAILABLE)) {
                continue;
            }
            if (engine().avail(hp, def).state() == state) {
                found.add(def);
            }
        }
        found.sort(java.util.Comparator.comparing(d -> d.id().id()));
        List<QuestDef> shown = found.subList(0, Math.min(20, found.size()));
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (QuestDef d : shown) {
            Component where =
                    d.startNpc() == null
                            ? Component.empty()
                            : api.messages()
                                    .get(
                                            "quest.journal.start_npc",
                                            Placeholder.component(
                                                    "npc", q.npcName(player, d.startNpc())));
            choices.add(new Dialogs.Choice(Component.text(d.name()), where));
        }
        choices.add(new Dialogs.Choice(msg().get("quest.dialog.back"), Component.empty()));
        api.dialogs()
                .choose(
                        player,
                        msg().get(
                                        state == QuestState.AVAILABLE
                                                ? "quest.journal.tab_available"
                                                : "quest.journal.tab_completed"),
                        List.of(
                                msg().get(
                                                shown.isEmpty()
                                                        ? "quest.journal.none"
                                                        : "quest.journal.list_hint")),
                        choices,
                        1,
                        index -> {
                            if (index < shown.size()) {
                                info(
                                        player,
                                        shown.get(index),
                                        state == QuestState.COMPLETED,
                                        () -> list(player, state));
                            } else {
                                journal(player);
                            }
                        });
    }

    /** 진행 중인 퀘스트 상세: 목표 진행, 보상, 추적·포기. */
    public void detail(Player player, QuestDef def) {
        HarborPlayer hp = api.players().get(player);
        Optional<QuestEntry> entry = hp.get(QuestEngine.COMPONENT).entry(def.id());
        List<Component> body = describe(player, def, entry.map(QuestEntry::progress));
        List<Dialogs.Choice> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add(new Dialogs.Choice(msg().get("quest.dialog.track"), Component.empty()));
        actions.add(() -> engine().track(player, def.id()));
        if (entry.isPresent()
                && entry.get().state() == QuestState.READY
                && def.turnInNpc().isEmpty()) {
            choices.add(new Dialogs.Choice(msg().get("quest.dialog.turn_in"), Component.empty()));
            actions.add(
                    () -> {
                        var _ = engine().turnIn(player, def.id());
                    });
        }
        if (def.abandonable() && !def.main()) {
            choices.add(
                    new Dialogs.Choice(
                            msg().get("quest.dialog.abandon"),
                            msg().get("quest.dialog.abandon_hint")));
            actions.add(() -> confirmAbandon(player, def));
        }
        choices.add(new Dialogs.Choice(msg().get("quest.dialog.back"), Component.empty()));
        actions.add(() -> journal(player));
        api.dialogs()
                .choose(
                        player,
                        Component.text(def.name()),
                        body,
                        choices,
                        1,
                        i -> actions.get(i).run());
    }

    void confirmAbandon(Player player, QuestDef def) {
        api.dialogs()
                .confirm(
                        player,
                        msg().get("quest.dialog.abandon"),
                        List.of(
                                msg().get(
                                                "quest.dialog.abandon_confirm",
                                                Placeholder.unparsed("name", def.name()))),
                        msg().get("quest.dialog.yes"),
                        msg().get("quest.dialog.no"),
                        ok -> {
                            if (ok) {
                                var _ = engine().abandon(player, def.id());
                            }
                        });
    }

    /** 정보만 보기 (완료·수락 가능 목록). 완료한 퀘스트는 목표를 모두 채운 것으로 보여 준다 (진행 기록은 완료 때 지워진다). */
    private void info(Player player, QuestDef def, boolean completed, Runnable back) {
        api.dialogs()
                .choose(
                        player,
                        Component.text(def.name()),
                        describe(player, def, Optional.empty(), completed),
                        List.of(
                                new Dialogs.Choice(
                                        msg().get("quest.dialog.back"), Component.empty())),
                        1,
                        i -> back.run());
    }

    // ------------------------------------------------------------------ NPC (quest:list)

    /**
     * NPC 대화의 '의뢰·이야기': 보고 가능 → 수락 가능 → 진행 중 → 주차 잠금 순. 이 NPC가 수집 의뢰 NPC면 수집 의뢰 게시판도 보여준다.
     *
     * @return 보여줄 것이 있었으면 true
     */
    public boolean npcList(Player player, NpcId npc) {
        HarborPlayer hp = api.players().get(player);
        List<Dialogs.Choice> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (QuestDef def : engine().defs()) {
            if (def.type().board()) {
                continue;
            }
            QuestEngine.Avail avail = engine().avail(hp, def);
            boolean starts = npc.equals(def.startNpc());
            boolean ends = def.turnInNpc().map(npc::equals).orElse(false);
            switch (avail.state()) {
                case READY -> {
                    if (ends) {
                        choices.add(
                                new Dialogs.Choice(
                                        msg().get(
                                                        "quest.npc.turn_in",
                                                        Placeholder.unparsed("name", def.name())),
                                        Component.empty()));
                        actions.add(
                                () -> {
                                    var _ = engine().turnIn(player, def.id());
                                });
                    }
                }
                case AVAILABLE -> {
                    if (starts) {
                        choices.add(
                                new Dialogs.Choice(
                                        msg().get(
                                                        def.main()
                                                                ? "quest.npc.offer_main"
                                                                : "quest.npc.offer",
                                                        Placeholder.unparsed("name", def.name())),
                                        Component.empty()));
                        actions.add(() -> offer(player, def));
                    }
                }
                case ACTIVE -> {
                    if (starts || ends || involves(def, npc)) {
                        choices.add(
                                new Dialogs.Choice(
                                        msg().get(
                                                        "quest.npc.active",
                                                        Placeholder.unparsed("name", def.name())),
                                        Component.empty()));
                        actions.add(() -> detail(player, def));
                    }
                }
                case LOCKED -> {
                    if (starts && avail.lockedWeek() > 0 && def.type() != QuestType.HIDDEN) {
                        choices.add(
                                new Dialogs.Choice(
                                        msg().get(
                                                        "quest.npc.week_locked",
                                                        Placeholder.unparsed("name", def.name()),
                                                        Placeholder.unparsed(
                                                                "week",
                                                                String.valueOf(avail.lockedWeek())),
                                                        Placeholder.unparsed(
                                                                "days",
                                                                String.valueOf(
                                                                        engine().daysUntilWeek(
                                                                                        avail
                                                                                                .lockedWeek())))),
                                        Component.empty()));
                        actions.add(() -> engine().explainLocked(player, def, avail));
                    }
                }
                default -> {}
            }
        }
        BoardRules rules = engine().boardRules();
        if (npc.equals(rules.collectNpc())) {
            choices.add(new Dialogs.Choice(msg().get("quest.board.collect"), Component.empty()));
            actions.add(() -> board(player, QuestType.COLLECT));
        }
        if (choices.isEmpty()) {
            return false;
        }
        api.dialogs()
                .choose(
                        player,
                        q.npcName(player, npc),
                        List.of(msg().get("quest.npc.body")),
                        choices,
                        1,
                        i -> actions.get(i).run());
        return true;
    }

    private static boolean involves(QuestDef def, NpcId npc) {
        for (Objective o : def.objectives()) {
            if (QuestEngine.npcOf(def, o).map(npc::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** 수락 제안: 설명·목표·보상을 보여주고 수락을 받는다. */
    public void offer(Player player, QuestDef def) {
        offer(player, def, ok -> {});
    }

    public void offer(Player player, QuestDef def, Consumer<Boolean> result) {
        api.dialogs()
                .confirm(
                        player,
                        Component.text(def.name()),
                        describe(player, def, Optional.empty()),
                        msg().get("quest.dialog.accept"),
                        msg().get("quest.dialog.decline"),
                        ok -> {
                            if (!ok) {
                                result.accept(false);
                                return;
                            }
                            var _ = engine().accept(player, def.id()).thenAccept(result);
                        });
    }

    // ------------------------------------------------------------------ 게시판

    /** 게시판 첫 화면: 일일·주간. */
    public void boardRoot(Player player) {
        HarborPlayer hp = api.players().get(player);
        List<Dialogs.Choice> choices =
                List.of(
                        new Dialogs.Choice(
                                msg().get(
                                                "quest.board.daily",
                                                Placeholder.unparsed(
                                                        "left",
                                                        String.valueOf(
                                                                q.remainingPicks(
                                                                        hp, QuestType.DAILY)))),
                                Component.empty()),
                        new Dialogs.Choice(
                                msg().get(
                                                "quest.board.weekly",
                                                Placeholder.unparsed(
                                                        "left",
                                                        String.valueOf(
                                                                q.remainingPicks(
                                                                        hp, QuestType.WEEKLY)))),
                                Component.empty()));
        api.dialogs()
                .choose(
                        player,
                        msg().get("quest.board.title"),
                        List.of(msg().get("quest.board.body")),
                        choices,
                        2,
                        i -> board(player, i == 0 ? QuestType.DAILY : QuestType.WEEKLY));
    }

    /** 게시판 후보 (초기화 뒤 처음 열 때 뽑는다). */
    public void board(Player player, QuestType kind) {
        var _ =
                q.ensureBoard(player, kind)
                        .whenComplete(
                                (board, error) -> {
                                    if (error != null || board == null) {
                                        msg().send(player, "quest.error.save");
                                        return;
                                    }
                                    showBoard(player, board);
                                });
    }

    private void showBoard(Player player, BoardState board) {
        HarborPlayer hp = api.players().get(player);
        List<QuestDef> offers = new ArrayList<>();
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (var id : board.offers()) {
            engine().def(id)
                    .ifPresent(
                            def -> {
                                offers.add(def);
                                QuestState state = engine().avail(hp, def).state();
                                String key =
                                        switch (state) {
                                            case ACTIVE, READY, COMPLETING ->
                                                    "quest.board.offer_active";
                                            case COMPLETED -> "quest.board.offer_done";
                                            case AVAILABLE -> "quest.board.offer";
                                            default -> "quest.board.offer_locked";
                                        };
                                Progress.Snapshot snap = q.preview(hp, def);
                                choices.add(
                                        new Dialogs.Choice(
                                                msg().get(
                                                                key,
                                                                Placeholder.unparsed(
                                                                        "name", def.name())),
                                                msg().get(
                                                                "quest.board.offer_reward",
                                                                Placeholder.unparsed(
                                                                        "money",
                                                                        String.valueOf(
                                                                                snap.gold())),
                                                                Placeholder.unparsed(
                                                                        "mulbineul",
                                                                        String.valueOf(
                                                                                snap.mulbineul())),
                                                                Placeholder.unparsed(
                                                                        "reputation",
                                                                        String.valueOf(
                                                                                snap
                                                                                        .reputation())))));
                            });
        }
        if (offers.isEmpty()) {
            msg().send(player, "quest.board.empty");
            return;
        }
        api.dialogs()
                .choose(
                        player,
                        msg().get("quest.board.title_" + board.kind().key()),
                        List.of(
                                msg().get(
                                                "quest.board.pick",
                                                Placeholder.unparsed(
                                                        "left",
                                                        String.valueOf(
                                                                q.remainingPicks(
                                                                        hp, board.kind()))))),
                        choices,
                        1,
                        i -> {
                            QuestDef def = offers.get(i);
                            QuestState state = engine().avail(hp, def).state();
                            if (state.open()) {
                                detail(player, def);
                            } else if (state == QuestState.AVAILABLE) {
                                offer(player, def);
                            } else {
                                engine().explainLocked(player, def, engine().avail(hp, def));
                            }
                        });
    }

    // ------------------------------------------------------------------ 본문

    List<Component> describe(Player player, QuestDef def, Optional<Progress> progress) {
        return describe(player, def, progress, false);
    }

    /** 설명·목표(진행)·보상 줄. completed면 모든 목표를 채운 것으로 그린다. */
    List<Component> describe(
            Player player, QuestDef def, Optional<Progress> progress, boolean completed) {
        List<Component> body = new ArrayList<>();
        for (String line : def.description()) {
            body.add(msg().get("quest.dialog.description", Placeholder.unparsed("text", line)));
        }
        body.add(msg().get("quest.dialog.objectives"));
        for (Objective o : def.objectives()) {
            int value = completed ? o.count() : progress.map(p -> p.count(o.key())).orElse(0);
            boolean done = completed || (o.tracked() && value >= o.count());
            body.add(
                    msg().get(
                                    done ? "quest.dialog.objective_done" : "quest.dialog.objective",
                                    Placeholder.unparsed(
                                            "text", engine().objectiveText(player, def, o)),
                                    Placeholder.unparsed(
                                            "value",
                                            o.tracked() || completed ? String.valueOf(value) : "-"),
                                    Placeholder.unparsed("count", String.valueOf(o.count()))));
        }
        Progress.Snapshot snap =
                progress.map(Progress::snap)
                        .orElseGet(() -> q.preview(api.players().get(player), def));
        body.add(
                msg().get(
                                "quest.dialog.rewards",
                                Placeholder.unparsed("money", String.valueOf(snap.gold())),
                                Placeholder.unparsed("mulbineul", String.valueOf(snap.mulbineul())),
                                Placeholder.unparsed("exp", String.valueOf(snap.exp())),
                                Placeholder.unparsed(
                                        "reputation", String.valueOf(snap.reputation()))));
        if (!def.rewards().items().isEmpty()
                || def.rewards().title() != null
                || def.rewards().chronicle() != null) {
            body.add(msg().get("quest.dialog.rewards_extra"));
        }
        return body;
    }

    private Component label(QuestDef def, QuestState state) {
        String key =
                switch (state) {
                    case READY -> "quest.journal.ready";
                    case COMPLETING -> "quest.journal.completing";
                    default -> def.main() ? "quest.journal.main" : "quest.journal.active";
                };
        return msg().get(key, Placeholder.unparsed("name", def.name()));
    }
}
