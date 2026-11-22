package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Codex reward definitions (codex.yml, proposed, docs 20 4.3).
 *
 * @param firstCatchMinGrade first registration at this grade or above gives water scales ("7.
 *     Balance design" ch. 7: 1 per species for rare and above)
 * @param regionTitlePrefix region completion title ID prefix (title = prefix/region name; Season
 *     rejects region titles not in titles.yml and only water scales are given)
 */
public record CodexRules(
        Grade firstCatchMinGrade,
        int firstCatchMulbineul,
        int regionCompleteMulbineul,
        String regionTitlePrefix,
        List<Milestone> totalMilestones,
        List<SeasonBuff> seasonBuffs) {

    /**
     * Cumulative completion stage. Titles are granted by Collection via Season TitleService;
     * cosmetics by Shop via CodexMilestoneEvent.
     */
    public record Milestone(int percent, String title) {}

    /** Season codex completion buff (source codex_season). */
    public record SeasonBuff(int percent, List<StatBonus> modifiers) {}

    public static final ContentType.Single<CodexRules> TYPE =
            new ContentType.Single<>("codex_rules", "codex.yml", false, CodexRules::parse);

    static CodexRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of("first_catch", "region_complete", "total_milestones", "season_buffs"));
        YamlNode first = node.get("first_catch").orElse(node);
        YamlNode region = node.get("region_complete").orElse(node);
        List<Milestone> milestones = new ArrayList<>();
        for (YamlNode m : node.list("total_milestones")) {
            m.allowOnly(Set.of("percent", "title"));
            String title = m.string("title");
            try {
                // Title definitions are in Season titles.yml (skipped in checks where the Season
                // type is not registered)
                ctx.ref("title", CodexTitles.titleId(title), m.pos());
            } catch (IllegalArgumentException e) {
                m.error("칭호 키 형식이 틀렸습니다: " + title);
            }
            milestones.add(new Milestone(m.integer("percent", 100, 1, 100), title));
        }
        List<SeasonBuff> buffs = new ArrayList<>();
        for (YamlNode b : node.list("season_buffs")) {
            b.allowOnly(Set.of("percent", "modifiers"));
            buffs.add(
                    new SeasonBuff(
                            b.integer("percent", 100, 1, 100),
                            StatBonus.parseList(b.list("modifiers"))));
        }
        milestones.sort(Comparator.comparingInt(Milestone::percent));
        buffs.sort(Comparator.comparingInt(SeasonBuff::percent));
        return new CodexRules(
                first.has("min_grade")
                        ? first.enumValue("min_grade", Grade.class).orElse(Grade.RARE)
                        : Grade.RARE,
                first.integer("mulbineul", 1, 0, 100),
                region.integer("mulbineul", 3, 0, 100),
                region.string("title_prefix", "codex_region"),
                List.copyOf(milestones),
                List.copyOf(buffs));
    }
}
