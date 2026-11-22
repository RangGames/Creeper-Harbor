package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.Speech;
import wiki.creeper.harbor.npc.api.SpeechChoice;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * Quest screens (docs 20 6.4 quest list): the /퀘스트 목록 Dialog (in progress, available, completed),
 * details (track, abandon), quest:list in NPC dialogs, accept offers, the board. Screens use only
 * the Core Dialog wrapper.
 */
public final class QuestDialogs {

    private final HarborApi api;
    private final QuestServices q;
    private final BoardDialogs board;

    public QuestDialogs(HarborApi api, QuestServices q) {
        this.api = api;
        this.q = q;
        this.board = new BoardDialogs(api, q, this);
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

    /** Available and completed lists (excluding board requests, up to 20). */
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

    /** Details of a quest in progress: objective progress, rewards, track and abandon. */
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

    /**
     * View only (completed and available lists). Completed quests are shown with all objectives
     * filled (progress records are cleared on completion).
     */
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
     * "Requests · stories" in NPC dialogs: ready to turn in → available → in progress → week
     * locked. If this NPC is the collection request NPC, also shows the collection request board.
     *
     * @return true if there was something to show
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
                        actions.add(() -> offer(player, def, npc));
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
        DialogStyle style = q.npcDialogs().styleOf(npc);
        if (!style.modal()) {
            // if the NPC speaks via speech bubble, chat etc., show the quest list the same way
            // (choices as chat buttons)
            q.npcDialogs()
                    .present(
                            player,
                            npc,
                            new Speech(
                                    style,
                                    null,
                                    List.of(msg().get("quest.npc.body")),
                                    choices.stream()
                                            .map(c -> new SpeechChoice(c.label(), c.tooltip()))
                                            .toList()),
                            i -> {
                                if (i >= 0 && i < actions.size()) {
                                    actions.get(i).run();
                                }
                            });
            return true;
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

    /** Accept offer: shows the description, objectives and rewards and asks for acceptance. */
    public void offer(Player player, QuestDef def) {
        offer(player, def, null, ok -> {});
    }

    /**
     * When an NPC offers. The style is the quest's offer_style → that NPC's dialog style (a
     * confirmation screen for Dialog).
     */
    public void offer(Player player, QuestDef def, @Nullable NpcId npc) {
        offer(player, def, npc, ok -> {});
    }

    public void offer(Player player, QuestDef def, Consumer<Boolean> result) {
        offer(player, def, null, result);
    }

    public void offer(Player player, QuestDef def, @Nullable NpcId npc, Consumer<Boolean> result) {
        DialogStyle style = offerStyle(def, npc);
        if (npc != null && !style.modal()) {
            speakOffer(player, def, npc, style, result);
            return;
        }
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

    private DialogStyle offerStyle(QuestDef def, @Nullable NpcId npc) {
        String raw = def.offerStyle();
        if (raw != null) {
            return DialogStyle.parse(raw).orElse(DialogStyle.DIALOG);
        }
        return npc == null ? DialogStyle.DIALOG : q.npcDialogs().styleOf(npc);
    }

    /**
     * Offer spoken by the NPC (the NPC is the speaker): the quest name, one page per description
     * line, the last page objectives and rewards. Choices are accept and later.
     */
    private void speakOffer(
            Player player, QuestDef def, NpcId npc, DialogStyle style, Consumer<Boolean> result) {
        List<Component> pages = new ArrayList<>();
        pages.add(msg().get("quest.offer.intro", Placeholder.unparsed("name", def.name())));
        for (String line : def.description()) {
            pages.add(msg().get("quest.dialog.description", Placeholder.unparsed("text", line)));
        }
        List<Component> rest = describe(player, def, Optional.empty());
        rest = rest.subList(Math.min(def.description().size(), rest.size()), rest.size());
        pages.add(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), rest));
        q.npcDialogs()
                .present(
                        player,
                        npc,
                        new Speech(
                                style,
                                null,
                                pages,
                                List.of(
                                        SpeechChoice.of(msg().get("quest.dialog.accept")),
                                        SpeechChoice.of(msg().get("quest.dialog.decline")))),
                        index -> {
                            if (index != 0) {
                                result.accept(false);
                                return;
                            }
                            var _ = engine().accept(player, def.id()).thenAccept(result);
                        });
    }

    // ------------------------------------------------------------------ Board

    /** Board home screen: daily and weekly. */
    public void boardRoot(Player player) {
        board.boardRoot(player);
    }

    /** Board candidates (drawn on the first open after a reset). */
    public void board(Player player, QuestType kind) {
        board.board(player, kind);
    }

    // ------------------------------------------------------------------ Body

    List<Component> describe(Player player, QuestDef def, Optional<Progress> progress) {
        return describe(player, def, progress, false);
    }

    /**
     * Description, objective (progress) and reward lines. If completed, all objectives are drawn as
     * filled.
     */
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
