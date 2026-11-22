package wiki.creeper.harbor.quest.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.fishing.api.SessionState;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.ArgCheck;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.DialogCondition;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.QuestMarker;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.ui.QuestDialogs;

/**
 * NPC integration (docs 20 5.2·6.2): dialog actions quest:list · quest:offer · quest:accept ·
 * quest:turn_in · quest:talk, conditions quest_state · quest_available, overhead markers
 * (MarkerProvider), blocking NPC clicks during the fishing minigame (busy check "minigame").
 */
public final class NpcLink {

    private static final java.util.Set<String> STATES =
            java.util.Set.of(
                    "locked",
                    "available",
                    "active",
                    "ready",
                    "completed",
                    "failed",
                    "open",
                    "not_started");

    private final QuestEngine engine;
    private final QuestDialogs dialogs;

    public NpcLink(QuestServices services, QuestDialogs dialogs) {
        this.engine = services.engine();
        this.dialogs = dialogs;
    }

    public void register(Plugin plugin) {
        var d = engine.deps.dialogs();
        d.registerAction(plugin, "quest:list", (ctx, args) -> list(ctx));
        d.registerAction(
                plugin,
                "quest:offer",
                action(
                        (ctx, args) ->
                                quest(args)
                                        .map(
                                                def -> {
                                                    dialogs.offer(ctx.player(), def, ctx.npc());
                                                    return ActionResult.HANDOFF;
                                                })
                                        .orElse(ActionResult.CLOSE),
                        true));
        d.registerAction(plugin, "quest:accept", action(this::accept, true));
        d.registerAction(plugin, "quest:turn_in", action(this::turnIn, false));
        d.registerAction(plugin, "quest:talk", (ctx, args) -> talk(ctx, args));
        d.registerCondition(plugin, "quest_state", condition(this::stateIs));
        d.registerCondition(
                plugin,
                "quest_available",
                condition(
                        (ctx, args) ->
                                quest(args)
                                        .map(
                                                def ->
                                                        engine.state(
                                                                        ctx.player().getUniqueId(),
                                                                        def.id())
                                                                == QuestState.AVAILABLE)
                                        .orElse(false)));
        engine.deps.markers().register(plugin, this::marker);
        // NPC busy check: blocks NPC clicks and cutscenes during the fishing minigame (message
        // npc.busy.minigame)
        engine.deps
                .npcs()
                .registerBusyCheck(
                        plugin,
                        "minigame",
                        p -> {
                            SessionState s = engine.deps.fishing().session(p.getUniqueId());
                            return s == SessionState.HOOKED || s == SessionState.MINIGAME;
                        });
    }

    // ------------------------------------------------------------------ Actions

    private ActionResult list(DialogContext ctx) {
        if (dialogs.npcList(ctx.player(), ctx.npc())) {
            return ActionResult.HANDOFF;
        }
        return ActionResult.failMessage("quest.npc.none");
    }

    private ActionResult accept(DialogContext ctx, DialogArgs args) {
        Optional<QuestDef> def = quest(args);
        if (def.isEmpty()) {
            return ActionResult.CLOSE;
        }
        CompletableFuture<ActionResult> result =
                engine.accept(ctx.player(), def.get().id())
                        .thenApply(
                                ok ->
                                        ok
                                                ? args.string("ok")
                                                        .map(ActionResult::goTo)
                                                        .orElse(ActionResult.CONTINUE)
                                                : ActionResult.fail(
                                                        args.string("fail").orElse(null)));
        return ActionResult.async(result);
    }

    private ActionResult turnIn(DialogContext ctx, DialogArgs args) {
        Optional<QuestDef> def = quest(args);
        if (def.isEmpty()) {
            // without a value, every quest that can be turned in to this NPC
            HarborPlayer hp = engine.api.players().get(ctx.player());
            for (var e : engine.open(hp)) {
                if (e.getValue().state() == QuestState.READY
                        && e.getKey().turnInNpc().map(ctx.npc()::equals).orElse(false)) {
                    var _ = engine.turnIn(ctx.player(), e.getKey().id());
                    return ActionResult.HANDOFF;
                }
            }
            return ActionResult.failMessage("quest.error.not_ready");
        }
        return ActionResult.async(
                engine.turnIn(ctx.player(), def.get().id())
                        .thenApply(
                                ok ->
                                        ok
                                                ? ActionResult.CLOSE
                                                : ActionResult.fail(
                                                        args.string("fail").orElse(null))));
    }

    /** talk objective (reaching a specific node): the current node if there is no node argument. */
    private ActionResult talk(DialogContext ctx, DialogArgs args) {
        String node = args.string("node").orElse(ctx.node());
        NpcId npc = ctx.npc();
        engine.progressWith(
                ctx.player(),
                "talk",
                (def, o) ->
                        node.equals(o.node())
                                && QuestEngine.npcOf(def, o).map(npc::equals).orElse(false),
                1,
                false);
        return ActionResult.CONTINUE;
    }

    // ------------------------------------------------------------------ Conditions

    private boolean stateIs(DialogContext ctx, DialogArgs args) {
        Optional<QuestDef> def = quest(args);
        if (def.isEmpty()) {
            return false;
        }
        QuestState state = engine.state(ctx.player().getUniqueId(), def.get().id());
        String want = args.string("state", "completed");
        return switch (want) {
            case "open" -> state.open();
            case "not_started" -> state == QuestState.LOCKED || state == QuestState.AVAILABLE;
            default -> state.key().equals(want);
        };
    }

    // ------------------------------------------------------------------ Markers

    /**
     * Priority: yellow ? (turn in) > gold ! (main accept) > ! (accept) > gray ? (in progress or
     * week locked). Hidden quests show no marker.
     */
    QuestMarker marker(Player player, NpcId npc) {
        Optional<HarborPlayer> found = engine.api.players().get(player.getUniqueId());
        if (found.isEmpty()) {
            return QuestMarker.NONE;
        }
        HarborPlayer hp = found.get();
        QuestMarker best = QuestMarker.NONE;
        for (QuestDef def : engine.defs()) {
            if (def.type().board() || def.type() == QuestType.HIDDEN) {
                continue;
            }
            boolean starts = npc.equals(def.startNpc());
            boolean ends = def.turnInNpc().map(npc::equals).orElse(false);
            if (!starts && !ends && !involves(def, npc)) {
                continue;
            }
            QuestEngine.Avail avail = engine.avail(hp, def);
            QuestMarker m =
                    switch (avail.state()) {
                        case READY -> ends ? QuestMarker.TURN_IN : QuestMarker.NONE;
                        case AVAILABLE ->
                                starts
                                        ? (def.main()
                                                ? QuestMarker.AVAILABLE_MAIN
                                                : QuestMarker.AVAILABLE)
                                        : QuestMarker.NONE;
                        case ACTIVE, COMPLETING -> QuestMarker.IN_PROGRESS;
                        case LOCKED ->
                                starts && avail.lockedWeek() > 0
                                        ? QuestMarker.IN_PROGRESS
                                        : QuestMarker.NONE;
                        default -> QuestMarker.NONE;
                    };
            best = QuestMarker.max(best, m);
            if (best == QuestMarker.TURN_IN) {
                return best;
            }
        }
        return best;
    }

    private static boolean involves(QuestDef def, NpcId npc) {
        for (Objective o : def.objectives()) {
            if ((o.type().equals("talk") || o.type().equals("deliver"))
                    && QuestEngine.npcOf(def, o).map(npc::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Helpers

    private Optional<QuestDef> quest(DialogArgs args) {
        return args.value()
                .or(() -> args.string("quest"))
                .flatMap(HarborId::tryParse)
                .flatMap(id -> engine.def(new QuestId(id)));
    }

    /** Argument check: whether the quest ID exists in the content. */
    private static void checkQuest(DialogArgs args, ArgCheck check, boolean required) {
        Optional<String> raw = args.value().or(() -> args.string("quest"));
        if (raw.isEmpty()) {
            if (required) {
                check.error("퀘스트 ID가 필요합니다 (quest:accept: quest/main/…)");
            }
            return;
        }
        Optional<HarborId> id = HarborId.tryParse(raw.get());
        ContentSnapshot s = check.snapshot();
        if (id.isEmpty()
                || !id.get().hasPrefix(QuestId.PREFIX)
                || !s.has(QuestDef.TYPE.name(), id.get())) {
            check.error("퀘스트가 없습니다: " + raw.get());
        }
    }

    private static DialogAction action(DialogAction run, boolean required) {
        return new DialogAction() {
            @Override
            public ActionResult run(DialogContext ctx, DialogArgs args) {
                return run.run(ctx, args);
            }

            @Override
            public void validate(DialogArgs args, ArgCheck check) {
                checkQuest(args, check, required);
            }
        };
    }

    private static DialogCondition condition(DialogCondition test) {
        return new DialogCondition() {
            @Override
            public boolean test(DialogContext ctx, DialogArgs args) {
                return test.test(ctx, args);
            }

            @Override
            public void validate(DialogArgs args, ArgCheck check) {
                checkQuest(args, check, true);
                String state = args.string("state", "completed");
                if (!STATES.contains(state)) {
                    check.error("state는 " + STATES + " 중 하나여야 합니다: " + state);
                }
            }
        };
    }
}
