package wiki.creeper.harbor.season.transition;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import wiki.creeper.harbor.season.content.ResetPlan;

/** reset_plan이 season 열 테이블을 모두 덮는지 (순수 계산, docs 20 11.3 reset_plan 검사). */
public final class PlanCoverage {

    private PlanCoverage() {}

    /** DB에 season 열이 있는데 계획에 없는 테이블. 비어 있어야 전환할 수 있다. */
    public static List<String> missing(ResetPlan plan, Set<String> seasonTables) {
        Set<String> result = new TreeSet<>(seasonTables);
        plan.tables().forEach(t -> result.remove(t.table()));
        return List.copyOf(result);
    }

    /** 계획에는 있는데 DB에 없는 테이블 (그 모듈이 아직 없음, 경고만). */
    public static List<String> absent(ResetPlan plan, Set<String> existingTables) {
        return plan.tables().stream()
                .map(ResetPlan.TableRule::table)
                .filter(t -> !existingTables.contains(t))
                .sorted()
                .toList();
    }
}
