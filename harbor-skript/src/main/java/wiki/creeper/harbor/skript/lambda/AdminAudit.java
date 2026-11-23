package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * Records every {@code harbor admin ...} effect run in admin_log through Core's AdminLogService
 * (actor {@code skript}). Whether a syntax element is audited is decided once at registration from
 * its patterns, so new admin effects are covered automatically.
 *
 * <p>Action key: see {@link AdminKeys#action}. Target: the player if exactly one player argument
 * value was given. Detail: script name and trigger line, then the argument values.
 */
public final class AdminAudit {

    static final String ACTOR = "skript";
    private static final int DETAIL_MAX = 1000;

    private AdminAudit() {}

    /**
     * Main thread: records one run. Never throws (an audit failure must not break the script).
     *
     * @param skip argument indices not to include (result variables of awaiting effects)
     */
    static void record(
            TriggerItem item, Event event, String label, Expression<?>[] exprs, int... skip) {
        try {
            @Nullable UUID target = null;
            int players = 0;
            StringBuilder detail = new StringBuilder(where(item));
            for (int i = 0; i < exprs.length; i++) {
                Expression<?> expr = exprs[i];
                if (expr == null || skipped(i, skip)) {
                    continue;
                }
                Object[] values = expr.getArray(event);
                List<String> shown = new ArrayList<>(values.length);
                for (Object v : values) {
                    if (v instanceof OfflinePlayer p) {
                        players++;
                        target = players == 1 ? p.getUniqueId() : null;
                        shown.add(String.valueOf(p.getName()));
                    } else {
                        shown.add(String.valueOf(v));
                    }
                }
                detail.append(" [").append(i).append("]=").append(String.join(",", shown));
            }
            String text =
                    detail.length() <= DETAIL_MAX
                            ? detail.toString()
                            : detail.substring(0, DETAIL_MAX);
            Harbor.api().adminLog().record(ACTOR, AdminKeys.action(label), target, text);
        } catch (RuntimeException e) {
            ch.njol.skript.Skript.warning(label + " 관리 기록 실패: " + String.valueOf(e.getMessage()));
        }
    }

    private static String where(TriggerItem item) {
        Trigger trigger = item.getTrigger();
        if (trigger == null) {
            return "effect-command";
        }
        Script script = trigger.getScript();
        String name = script == null ? "?" : script.name();
        return name + ":" + trigger.getLineNumber() + " (" + trigger.getName() + ")";
    }

    private static boolean skipped(int index, int[] skip) {
        for (int s : skip) {
            if (s == index) {
                return true;
            }
        }
        return false;
    }
}
