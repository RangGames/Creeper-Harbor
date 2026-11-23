package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.Changer.ChangerUtils;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * Effect that waits for an asynchronous Harbor result without blocking the main thread, stores it
 * in the given variables and then continues the trigger (like {@code wait}).
 *
 * <p>Target arguments (indices given at registration) must be changeable (variables). If every
 * target is optional and none was written, the call is fire-and-forget and the trigger continues at
 * once. If the result never arrives (for example a dialog closed with ESC), the rest of the trigger
 * does not run.
 */
public final class AwaitEffect extends Effect {

    /**
     * Starts the work. Returns one value list per target (in target order), or null if there is
     * nothing to wait for (bad arguments: the targets are deleted and the trigger continues).
     */
    @FunctionalInterface
    public interface Start {
        @Nullable CompletableFuture<List<?>[]> start(Ctx ctx);
    }

    private final String label;
    private final int[] targets;
    private final Start start;
    private Expression<?>[] exprs = new Expression<?>[0];
    private int pattern;
    private int mark;
    private boolean waits;
    private final boolean audited;

    AwaitEffect(String label, int[] targets, Start start, boolean audited) {
        this.label = label;
        this.targets = targets.clone();
        this.start = start;
        this.audited = audited;
    }

    @Override
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        this.exprs = exprs;
        this.pattern = pattern;
        this.mark = parseResult.mark;
        for (int index : targets) {
            if (index >= exprs.length || exprs[index] == null) {
                continue;
            }
            if (!ChangerUtils.acceptsChange(exprs[index], ChangeMode.SET, Object.class)) {
                Skript.error("'" + label + "'의 결과는 변수에만 담을 수 있습니다");
                return false;
            }
            waits = true;
        }
        if (waits) {
            getParser().setHasDelayBefore(Kleenean.TRUE);
        }
        return true;
    }

    @Override
    protected @Nullable TriggerItem walk(Event event) {
        debug(event, true);
        Ctx ctx = new Ctx(event, exprs, pattern, mark);
        CompletableFuture<List<?>[]> future;
        try {
            future = start.start(ctx);
        } catch (RuntimeException e) {
            Skript.warning(label + " 실행 오류: " + String.valueOf(e.getMessage()));
            future = null;
        }
        if (audited) {
            AdminAudit.record(this, event, label, exprs, targets);
        }
        if (!waits) {
            return getNext();
        }
        if (future == null || !Skript.getInstance().isEnabled()) {
            store(event, null);
            return getNext();
        }
        TriggerItem next = getNext();
        Object locals = Variables.removeLocals(event);
        var _ =
                future.whenComplete(
                        (values, error) ->
                                onMain(
                                        () -> {
                                            Delay.addDelayedEvent(event);
                                            if (locals != null) {
                                                Variables.setLocalVariables(event, locals);
                                            }
                                            store(event, error == null ? values : null);
                                            if (next != null) {
                                                TriggerItem.walk(next, event);
                                            }
                                            Variables.removeLocals(event);
                                        }));
        return null;
    }

    private void store(Event event, List<?> @Nullable [] values) {
        for (int i = 0; i < targets.length; i++) {
            int index = targets[i];
            if (index >= exprs.length || exprs[index] == null) {
                continue;
            }
            List<?> list = values != null && i < values.length ? values[i] : null;
            try {
                if (list == null || list.isEmpty()) {
                    exprs[index].change(event, null, ChangeMode.DELETE);
                } else {
                    exprs[index].change(event, list.toArray(), ChangeMode.SET);
                }
            } catch (RuntimeException e) {
                Skript.warning(label + " 결과 저장 오류: " + String.valueOf(e.getMessage()));
            }
        }
    }

    private static void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (Skript.getInstance().isEnabled()) {
            Bukkit.getScheduler().runTask(Skript.getInstance(), task);
        }
    }

    @Override
    protected void execute(Event event) {
        // walk() is overridden; never called
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return label;
    }
}
