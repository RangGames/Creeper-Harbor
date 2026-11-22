package wiki.creeper.harbor.jobs.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.jobs.service.LevelCurve;

/**
 * Jobs content types and checks. Uses no server API, so harbor-tools (CI) checks with the same
 * code.
 */
public final class JobsContent {

    public static final List<ContentType<?>> TYPES = List.of(SkillNode.TYPE, JobLineDef.TYPE);

    private JobsContent() {}

    /**
     * Rejects the reload if the level curve differs from the '7. Balance design' 3.1 table
     * (jobs.curve_check).
     */
    public static final ContentCheck<Balance> CURVE_CHECK =
            (balance, check) -> {
                JobsBalance jobs = new JobsBalance(balance);
                LevelCurve curve = LevelCurve.of(jobs);
                for (int[] expected : jobs.curveChecks()) {
                    int level = expected[0];
                    if (level < 1 || curve.cumulative(level) != expected[1]) {
                        check.error(
                                "jobs.curve_check: Lv"
                                        + level
                                        + " 누적 EXP가 "
                                        + curve.cumulative(Math.max(1, level))
                                        + "인데 표는 "
                                        + expected[1]
                                        + "입니다");
                    }
                }
            };

    /** Whether line tree node lines and capstone definitions match each other. */
    public static final ContentCheck<JobLineDef> CAPSTONE_CHECK =
            (def, check) -> {
                JobLineDef.Capstone capstone = def.capstone();
                if (capstone == null) {
                    return;
                }
                check.snapshot()
                        .get(SkillNode.TYPE, capstone.node().id())
                        .ifPresent(
                                node -> {
                                    if (node.kind() != SkillNode.Kind.CAPSTONE
                                            || node.line() != def.line()) {
                                        check.error(
                                                "capstone.node '"
                                                        + capstone.node()
                                                        + "'는 "
                                                        + def.line().key()
                                                        + " 계열의 캡스톤 노드가 아닙니다");
                                    }
                                });
            };
}
