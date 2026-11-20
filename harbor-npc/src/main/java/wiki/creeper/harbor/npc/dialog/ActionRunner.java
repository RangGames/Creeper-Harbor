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
 * 대화 트리 선택지의 조건 평가와 액션 실행 (docs 20 5.4 대화 흐름 5~6단계). 액션을 순서대로 돌리고 Continue가 아니면 결과(GoTo·Page·
 * Close·Handoff·Fail)를 반영한다. Async는 메인 스레드로 돌아와 세션·nonce가 그대로일 때만 이어 간다. 메인 스레드 전용.
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

    /** 조건 하나. 등록되지 않은 키는 거짓 (선택지를 숨김). */
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

    /** 선택지를 보여줄지: 조건이 맞고, 액션 키가 모두 등록되어 있어야 한다. */
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
        // 화면을 띄운 뒤 상태가 바뀌었을 수 있어 다시 평가한다
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
                                        return; // 그 사이 세션이 끝났거나 바뀜
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

    /** Continue·Async가 아닌 결과 처리. */
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
