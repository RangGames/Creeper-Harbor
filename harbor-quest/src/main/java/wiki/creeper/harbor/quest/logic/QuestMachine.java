package wiki.creeper.harbor.quest.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.ObjectiveMode;
import wiki.creeper.harbor.quest.content.QuestDef;

/**
 * Quest state machine and objective progress computation (docs 20 6.4). Pure computation only (no
 * server API or I/O).
 *
 * <pre>
 * LOCKED ⇄ AVAILABLE → ACTIVE → READY → COMPLETING → COMPLETED (repeatable: AVAILABLE in the next cycle)
 *                         ↘ ABANDONED(row deleted) · FAILED(event period ended)        ↘ READY on failure
 * </pre>
 */
public final class QuestMachine {

    private QuestMachine() {}

    /** Progress result. */
    public record Step(Progress progress, boolean changed, List<String> completedKeys) {}

    /**
     * Objectives currently progressing (the single current objective for sequence, all unfilled
     * objectives for all). deliver is excluded since it is checked on turn-in.
     */
    public static List<Objective> current(QuestDef quest, Progress progress) {
        List<Objective> result = new ArrayList<>();
        List<Objective> objectives = quest.objectives();
        if (quest.mode() == ObjectiveMode.SEQUENCE) {
            int i = progress.index();
            if (i >= 0 && i < objectives.size()) {
                Objective o = objectives.get(i);
                if (o.tracked() && progress.count(o.key()) < o.count()) {
                    result.add(o);
                }
            }
            return result;
        }
        for (Objective o : objectives) {
            if (o.tracked() && progress.count(o.key()) < o.count()) {
                result.add(o);
            }
        }
        return result;
    }

    /** Whether all tracked objectives are filled (excluding deliver). */
    public static boolean ready(QuestDef quest, Progress progress) {
        for (Objective o : quest.objectives()) {
            if (o.tracked() && progress.count(o.key()) < o.count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Advances the objective by amount. Nothing changes if it is not a currently progressing
     * objective (protects sequence order). Never exceeds the objective count.
     */
    public static Step add(QuestDef quest, Progress progress, String key, int amount) {
        if (amount <= 0) {
            return new Step(progress, false, List.of());
        }
        Objective target = activeObjective(quest, progress, key);
        if (target == null) {
            return new Step(progress, false, List.of());
        }
        int before = progress.count(key);
        int after = (int) Math.min((long) target.count(), (long) before + amount);
        return apply(quest, progress, key, before, after, target);
    }

    /**
     * Gauge objective (reached value): overwritten with the current value. It never goes down (an
     * objective once filled stays filled).
     */
    public static Step set(QuestDef quest, Progress progress, String key, int value) {
        Objective target = activeObjective(quest, progress, key);
        if (target == null) {
            return new Step(progress, false, List.of());
        }
        int before = progress.count(key);
        int after = Math.min(target.count(), Math.max(before, value));
        return apply(quest, progress, key, before, after, target);
    }

    /** Admin command: sets the value as is (regardless of order). */
    public static Progress force(QuestDef quest, Progress progress, String key, int value) {
        Map<String, Integer> counts = new LinkedHashMap<>(progress.counts());
        quest.objective(key)
                .ifPresent(o -> counts.put(key, Math.max(0, Math.min(o.count(), value))));
        return progress.withCounts(counts, firstOpen(quest, counts));
    }

    private static Step apply(
            QuestDef quest,
            Progress progress,
            String key,
            int before,
            int after,
            Objective target) {
        if (after == before) {
            return new Step(progress, false, List.of());
        }
        Map<String, Integer> counts = new LinkedHashMap<>(progress.counts());
        counts.put(key, after);
        int index = quest.mode() == ObjectiveMode.SEQUENCE ? firstOpen(quest, counts) : 0;
        List<String> done = after >= target.count() ? List.of(key) : List.of();
        return new Step(progress.withCounts(counts, index), true, done);
    }

    private static @Nullable Objective activeObjective(
            QuestDef quest, Progress progress, String key) {
        for (Objective o : current(quest, progress)) {
            if (o.key().equals(key)) {
                return o;
            }
        }
        return null;
    }

    /**
     * sequence: index of the first unfilled tracked objective (the objective count if all are
     * filled).
     */
    static int firstOpen(QuestDef quest, Map<String, Integer> counts) {
        List<Objective> objectives = quest.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            Objective o = objectives.get(i);
            if (o.tracked() && counts.getOrDefault(o.key(), 0) < o.count()) {
                return i;
            }
        }
        return objectives.size();
    }

    /**
     * Progress migration on reload (docs 20 6.4 edge cases): carries progress over by objective
     * key. Removed objectives are dropped, excess is trimmed when the count shrinks, and new
     * objectives start at 0.
     */
    public static Progress migrate(QuestDef quest, Progress progress) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Objective o : quest.objectives()) {
            int value = progress.count(o.key());
            if (value > 0) {
                counts.put(o.key(), Math.min(value, o.count()));
            }
        }
        int index = quest.mode() == ObjectiveMode.SEQUENCE ? firstOpen(quest, counts) : 0;
        return progress.withCounts(counts, index).withVersion(quest.hash());
    }

    /**
     * State after progress: READY when all tracked objectives are filled, ACTIVE when a reload made
     * it unfilled again.
     */
    public static QuestState afterProgress(QuestState state, boolean ready) {
        if (state == QuestState.ACTIVE && ready) {
            return QuestState.READY;
        }
        if (state == QuestState.READY && !ready) {
            return QuestState.ACTIVE;
        }
        return state;
    }

    /**
     * Whether the state can be accepted (repeatable quests can accept again from COMPLETED once the
     * cycle changes).
     */
    public static boolean canAccept(QuestState state, boolean sameCycle) {
        return switch (state) {
            case LOCKED, ACTIVE, READY, COMPLETING, ORPHANED -> false;
            case AVAILABLE, ABANDONED -> true;
            case COMPLETED, FAILED -> !sameCycle;
        };
    }

    public static boolean canAbandon(QuestDef quest, QuestState state) {
        return quest.abandonable()
                && !quest.main()
                && (state == QuestState.ACTIVE || state == QuestState.READY);
    }

    public static boolean canTurnIn(QuestState state) {
        return state == QuestState.READY;
    }
}
