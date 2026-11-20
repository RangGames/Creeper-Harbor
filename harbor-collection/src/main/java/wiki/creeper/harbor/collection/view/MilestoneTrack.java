package wiki.creeper.harbor.collection.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import wiki.creeper.harbor.collection.content.CodexRules;
import wiki.creeper.harbor.collection.content.StatBonus;

/**
 * 도감 보상 트랙 (추가 제안, 서버 API 없음). codex.yml의 누적 달성률 칭호(total_milestones)와 시즌 버프(season_buffs)를 달성률 순으로
 * 늘어놓고 달성 여부를 붙인다. 지급은 지금처럼 Collection이 자동으로 한다 (칭호: 달성 순간 Season TitleService, 버프: 시즌 달성률로 다시 계산).
 * 이 클래스는 보여주기만 한다.
 */
public final class MilestoneTrack {

    /** 트랙 종류. */
    public enum Kind {
        /** 누적 도감 칭호. */
        TOTAL_TITLE,
        /** 시즌 도감 버프. */
        SEASON_BUFF
    }

    /**
     * 한 단계.
     *
     * @param title 칭호 키 (TOTAL_TITLE, 없으면 빈 글)
     * @param modifiers 버프 (SEASON_BUFF)
     * @param reached 지금 달성률이 이 단계 이상인지
     */
    public record Step(
            Kind kind, int percent, String title, List<StatBonus> modifiers, boolean reached) {}

    private MilestoneTrack() {}

    /** 누적 칭호 단계 + 시즌 버프 단계 (종류별로 달성률 순). */
    public static List<Step> steps(CodexRules rules, double totalPercent, double seasonPercent) {
        List<Step> out = new ArrayList<>();
        rules.totalMilestones().stream()
                .sorted(Comparator.comparingInt(CodexRules.Milestone::percent))
                .forEach(
                        m ->
                                out.add(
                                        new Step(
                                                Kind.TOTAL_TITLE,
                                                m.percent(),
                                                m.title(),
                                                List.of(),
                                                totalPercent >= m.percent())));
        rules.seasonBuffs().stream()
                .sorted(Comparator.comparingInt(CodexRules.SeasonBuff::percent))
                .forEach(
                        b ->
                                out.add(
                                        new Step(
                                                Kind.SEASON_BUFF,
                                                b.percent(),
                                                "",
                                                b.modifiers(),
                                                seasonPercent >= b.percent())));
        return List.copyOf(out);
    }

    /** 그 종류에서 아직 못 받은 첫 단계의 달성률 (모두 받았으면 비어 있음). */
    public static OptionalInt next(List<Step> steps, Kind kind) {
        return steps.stream()
                .filter(s -> s.kind() == kind && !s.reached())
                .mapToInt(Step::percent)
                .min();
    }
}
