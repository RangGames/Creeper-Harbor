package wiki.creeper.harbor.npc.dialog;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogCondition;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.DialogEndReason;
import wiki.creeper.harbor.npc.api.event.DialogOptionSelectedEvent;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;
import wiki.creeper.harbor.npc.logic.DialogEval;

/**
 * Condition evaluation and action execution for dialog tree choices (docs 20 5.4 dialog flow steps
 * 5-6). Runs actions in order and, on a non-Continue result, applies it (GoTo, Page, Close,
 * Handoff, Fail). Async continues only after returning to the main thread with the session and
 * nonce unchanged. Main thread only.
 */
final class ActionRunner {

    private final HarborApi api;
    private final Tasks tasks;
    private final DialogRegistry registry;
    private final Logger log;
    private final DialogManager manager;

    ActionRunner(
            HarborApi api,
            Tasks tasks,
            DialogRegistry registry,
            Logger log,
            DialogManager manager) {
        this.api = api;
        this.tasks = tasks;
        this.registry = registry;
        this.log = log;
        this.manager = manager;
    }

    /** A single condition. Unregistered keys are false (hide the choice). */
    boolean test(DialogContext ctx, DialogRef ref) {
        Optional<DialogCondition> condition = registry.condition(ref.key());
        if (condition.isEmpty()) {
            registry.warnOnce(ref, "조건");
            return false;
        }
        try {
            return condition.get().test(ctx, ref.args());
        } catch (RuntimeException e) {
            log.warn("대화 조건 {} 평가 오류 ({})", ref.key(), ref.pos(), e);
            return false;
        }
    }

    /** Whether to show a choice: conditions must match and all action keys must be registered. */
    boolean available(DialogContext ctx, DialogDef.Option option) {
        for (DialogRef action : option.actions()) {
            if (!registry.hasAction(action.key())) {
                registry.warnOnce(action, "액션");
                return false;
            }
        }
        return DialogEval.all(option.conditions(), ref -> test(ctx, ref));
    }

    void choose(Player player, ActiveSession s, int index) {
        DialogDef def = s.def;
        DialogDef.Node node = def == null ? null : def.nodes().get(s.node);
        if (def == null || node == null || index >= node.options().size()) {
            manager.end(s, DialogEndReason.CLOSED);
            return;
        }
        DialogDef.Option option = node.options().get(index);
        // State may have changed since the screen was shown, so evaluate again
        if (!available(manager.context(player, s), option)) {
            api.messages().send(player, "npc.dialog.unavailable");
            manager.render(player, s);
            return;
        }
        Bukkit.getPluginManager()
                .callEvent(new DialogOptionSelectedEvent(player, s.npc, def.id(), s.node, index));
        run(player, s, option, 0);
    }

    private void run(Player player, ActiveSession s, DialogDef.Option option, int from) {
        List<DialogRef> actions = option.actions();
        for (int i = from; i < actions.size(); i++) {
            DialogRef ref = actions.get(i);
            Optional<DialogAction> action = registry.action(ref.key());
            if (action.isEmpty()) {
                registry.warnOnce(ref, "액션");
                continue;
            }
            ActionResult result;
            try {
                result = action.get().run(manager.context(player, s), ref.args());
            } catch (RuntimeException e) {
                log.error("대화 액션 {} 실행 오류 ({})", ref.key(), ref.pos(), e);
                result = ActionResult.failMessage("npc.dialog.error");
            }
            if (result instanceof ActionResult.Continue) {
                continue;
            }
            if (result instanceof ActionResult.Async(var future)) {
                int next = i + 1;
                s.busy = true;
                long nonce = s.nonce;
                var _ =
                        future.whenCompleteAsync(
                                (r, ex) -> {
                                    s.busy = false;
                                    if (manager.sessions().get(s.player) != s
                                            || s.nonce != nonce
                                            || !player.isOnline()) {
                                        return; // The session ended or changed in the meantime
                                    }
                                    ActionResult done = r;
                                    if (ex != null || done == null) {
                                        log.error("대화 액션 {} 비동기 오류 ({})", ref.key(), ref.pos(), ex);
                                        done = ActionResult.failMessage("npc.dialog.error");
                                    }
                                    if (done instanceof ActionResult.Continue) {
                                        run(player, s, option, next);
                                    } else {
                                        apply(player, s, done);
                                    }
                                },
                                tasks.main());
                return;
            }
            apply(player, s, result);
            return;
        }
        String target = option.gotoNode();
        if (target != null) {
            manager.goTo(player, s, target, log);
        } else {
            manager.end(s, DialogEndReason.CLOSED);
            player.closeDialog();
        }
    }

    /** Handles results other than Continue and Async. */
    private void apply(Player player, ActiveSession s, ActionResult result) {
        switch (result) {
            case ActionResult.GoTo(String node) -> manager.goTo(player, s, node, log);
            case ActionResult.Page(int page) -> {
                s.page = page;
                manager.render(player, s);
            }
            case ActionResult.Close() -> {
                manager.end(s, DialogEndReason.CLOSED);
                player.closeDialog();
            }
            case ActionResult.Handoff() -> manager.end(s, DialogEndReason.HANDOFF);
            case ActionResult.Fail(String node, String key) -> {
                if (key != null) {
                    api.messages().send(player, key);
                }
                DialogDef def = s.def;
                if (node != null && def != null && def.nodes().containsKey(node)) {
                    manager.goTo(player, s, node, log);
                } else {
                    manager.render(player, s);
                }
            }
            case ActionResult.Continue c -> manager.render(player, s);
            case ActionResult.Async a -> manager.render(player, s);
        }
    }
}
