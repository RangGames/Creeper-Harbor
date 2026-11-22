package wiki.creeper.harbor.collection.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import wiki.creeper.harbor.collection.content.CodexRules;
import wiki.creeper.harbor.collection.content.StatBonus;

/**
 * Codex reward track (proposed, no server API). Lays out codex.yml's cumulative completion titles
 * (total_milestones) and season buffs (season_buffs) in completion order and marks whether each is
 * reached. Granting is still automatic by Collection (titles: Season TitleService at the moment
 * reached; buffs: recomputed from season completion). This class only displays.
 */
public final class MilestoneTrack {

    /** Track kind. */
    public enum Kind {
        /** Cumulative codex title. */
        TOTAL_TITLE,
        /** Season codex buff. */
        SEASON_BUFF
    }

    /**
     * One stage.
     *
     * @param title title key (TOTAL_TITLE; empty string if none)
     * @param modifiers buffs (SEASON_BUFF)
     * @param reached whether the current completion is at or above this stage
     */
    public record Step(
            Kind kind, int percent, String title, List<StatBonus> modifiers, boolean reached) {}

    private MilestoneTrack() {}

    /** Cumulative title stages + season buff stages (each kind in completion order). */
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

    /** Completion of the first not-yet-received stage of that kind (empty if all received). */
    public static OptionalInt next(List<Step> steps, Kind kind) {
        return steps.stream()
                .filter(s -> s.kind() == kind && !s.reached())
                .mapToInt(Step::percent)
                .min();
    }
}
