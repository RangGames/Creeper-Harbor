package wiki.creeper.harbor.season.transition;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import wiki.creeper.harbor.season.content.ResetPlan;

/**
 * Whether reset_plan covers every table with a season column (pure computation, docs 20 11.3
 * reset_plan check).
 */
public final class PlanCoverage {

    private PlanCoverage() {}

    /**
     * Tables that have a season column in the DB but are missing from the plan. Must be empty for
     * the transition to run.
     */
    public static List<String> missing(ResetPlan plan, Set<String> seasonTables) {
        Set<String> result = new TreeSet<>(seasonTables);
        plan.tables().forEach(t -> result.remove(t.table()));
        return List.copyOf(result);
    }

    /**
     * Tables in the plan that are not in the DB (that module is not installed yet; warning only).
     */
    public static List<String> absent(ResetPlan plan, Set<String> existingTables) {
        return plan.tables().stream()
                .map(ResetPlan.TableRule::table)
                .filter(t -> !existingTables.contains(t))
                .sorted()
                .toList();
    }
}
