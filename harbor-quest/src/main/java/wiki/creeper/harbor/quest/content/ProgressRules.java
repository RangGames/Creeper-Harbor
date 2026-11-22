package wiki.creeper.harbor.quest.content;

import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Server progress and climax unlock conditions (progress.yml, proposed, docs 20 6.4). All proposed
 * values.
 *
 * @param donationRate aquarium donation rate threshold (0~1)
 * @param chapter chapter whose clear count is counted (ch. 7)
 * @param minClears minimum clear count
 * @param clearRatio clear ratio relative to season active players
 * @param checkMinutes check interval (minutes)
 */
public record ProgressRules(
        double donationRate,
        int chapter,
        int minClears,
        double clearRatio,
        int checkMinutes,
        boolean announce) {

    public static final ContentType.Single<ProgressRules> TYPE =
            new ContentType.Single<>(
                    "quest_server_progress", "progress.yml", false, ProgressRules::parse);

    static ProgressRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("climax", "check_interval_minutes", "announce"));
        boolean nested = node.has("climax");
        YamlNode climax = node.get("climax").orElse(node);
        if (nested) {
            climax.allowOnly(Set.of("donation_rate", "chapter", "min_clears", "clear_ratio"));
        }
        double rate = climax.number("donation_rate", 0.8);
        if (rate < 0 || rate > 1) {
            climax.error("donation_rate는 0~1이어야 합니다: " + rate);
        }
        return new ProgressRules(
                rate,
                climax.integer("chapter", 7, 0, 8),
                climax.integer("min_clears", 50, 0, 1_000_000),
                climax.number("clear_ratio", 0.15),
                node.integer("check_interval_minutes", 5, 1, 1440),
                node.bool("announce", true));
    }
}
