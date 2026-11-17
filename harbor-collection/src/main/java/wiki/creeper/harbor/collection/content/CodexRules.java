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
 * 도감 보상 정의 (codex.yml, 추가 제안, docs 20 4.3).
 *
 * @param firstCatchMinGrade 이 등급 이상 첫 등록이면 물비늘 ('7. 밸런스 기획' 7장: 희귀 이상 종당 1)
 * @param regionTitlePrefix 지역 완성 칭호 ID 접두사 (칭호 = 접두사/지역 이름)
 */
public record CodexRules(
        Grade firstCatchMinGrade,
        int firstCatchMulbineul,
        int regionCompleteMulbineul,
        String regionTitlePrefix,
        List<Milestone> totalMilestones,
        List<SeasonBuff> seasonBuffs) {

    /** 누적 달성률 단계. 칭호는 Season, 치장품은 Shop이 CodexMilestoneEvent로 지급한다. */
    public record Milestone(int percent, String title) {}

    /** 시즌 도감 달성률 버프 (출처 codex_season). */
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
            milestones.add(new Milestone(m.integer("percent", 100, 1, 100), m.string("title")));
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
