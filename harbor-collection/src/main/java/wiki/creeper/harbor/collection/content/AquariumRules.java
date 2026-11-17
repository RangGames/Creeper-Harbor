package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 수족관 규칙 (aquarium.yml, 추가 제안, docs 20 4.4). 점수는 개인 기여 순위에만 쓰고, 단계는 기부율(서로 다른 어종 수 ÷ 기부 가능 어종 수)로
 * 판정한다.
 *
 * @param qualityBonus 품질 보너스 × (1 + qualityBonus × (★ − 1))
 * @param firstSpeciesMult 서버에 처음 기부되는 어종 점수 배율
 * @param perPlayerSpeciesCap 같은 어종을 한 사람이 시즌에 점수 인정받는 최대 마리 수
 */
public record AquariumRules(
        Map<Grade, Integer> points,
        double qualityBonus,
        double firstSpeciesMult,
        int perPlayerSpeciesCap,
        List<Stage> stages) {

    /** 단계: 기부율 rate 이상이면 서버 플래그 flag를 켜고 서버 버프 modifiers를 건다. */
    public record Stage(int stage, double rate, String flag, List<StatBonus> modifiers) {}

    public static final ContentType.Single<AquariumRules> TYPE =
            new ContentType.Single<>("aquarium", "aquarium.yml", false, AquariumRules::parse);

    public int pointsFor(Grade grade, int stars, boolean firstSpecies) {
        double base = points.getOrDefault(grade, 0) * (1 + qualityBonus * (stars - 1));
        return (int) Math.round(firstSpecies ? base * firstSpeciesMult : base);
    }

    static AquariumRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "points",
                        "quality_bonus",
                        "first_species_mult",
                        "per_player_species_cap",
                        "stages"));
        Map<Grade, Integer> points = new EnumMap<>(Grade.class);
        node.get("points")
                .ifPresent(
                        p ->
                                p.entries()
                                        .forEach(
                                                (k, v) ->
                                                        Keyed.fromKey(Grade.class, k)
                                                                .ifPresentOrElse(
                                                                        g ->
                                                                                points.put(
                                                                                        g,
                                                                                        v.asInt()),
                                                                        () ->
                                                                                v.error(
                                                                                        "등급 이름이 아닙니다: "
                                                                                                + k))));
        List<Stage> stages = new ArrayList<>();
        for (YamlNode s : node.list("stages")) {
            s.allowOnly(Set.of("stage", "rate", "flag", "modifiers"));
            double rate = s.number("rate");
            if (rate <= 0 || rate > 1) {
                s.error("rate는 0~1 사이여야 합니다");
            }
            stages.add(
                    new Stage(
                            s.integer("stage", 1, 1, 100),
                            rate,
                            s.string("flag"),
                            StatBonus.parseList(s.list("modifiers"))));
        }
        stages.sort(Comparator.comparingInt(Stage::stage));
        return new AquariumRules(
                Map.copyOf(points),
                node.number("quality_bonus", 0.1),
                node.number("first_species_mult", 3),
                node.integer("per_player_species_cap", 5, 1, 1000),
                List.copyOf(stages));
    }
}
