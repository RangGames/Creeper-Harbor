package wiki.creeper.harbor.jobs.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.jobs.service.LevelCurve;

/** Jobs 콘텐츠 형식과 검사. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class JobsContent {

    public static final List<ContentType<?>> TYPES = List.of(SkillNode.TYPE, JobLineDef.TYPE);

    private JobsContent() {}

    /** 레벨 곡선이 '7. 밸런스 기획' 3.1 표(jobs.curve_check)와 다르면 리로드를 거부한다. */
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

    /** 계열 트리 노드의 line과 캡스톤 정의가 서로 맞는지. */
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
