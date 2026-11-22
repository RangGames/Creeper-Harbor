package wiki.creeper.harbor.collection.content;

import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.core.api.StatKey;

/**
 * Release rules (release.yml, proposed, docs 20 4.4 "release"). Numbers are proposed values.
 *
 * @param flag release opens only when this server flag is set ("5. Server story" ch. 6, after the
 *     climax)
 * @param rewardMinGrade only this grade or above gives reputation and the luck buff
 * @param reputation reputation (0 means the NPC balance.yml reputation.sources.release default)
 * @param buffStat luck buff stat (size_luck)
 * @param buffValue buff value (ADD)
 * @param buffMinutes buff duration (releasing again only restarts the duration; does not stack)
 */
public record ReleaseRules(
        String flag,
        Grade rewardMinGrade,
        int reputation,
        StatKey buffStat,
        double buffValue,
        int buffMinutes) {

    /** Check result. */
    public enum Verdict {
        ALLOWED,
        LOCKED,
        NOT_FISH,
        RELEASE_ONLY
    }

    public static final ReleaseRules DEFAULT =
            new ReleaseRules("climax_done", Grade.RARE, 0, StatKey.of("size_luck"), 0.02, 30);

    public static final ContentType.Single<ReleaseRules> TYPE =
            new ContentType.Single<>("release", "release.yml", false, ReleaseRules::parse);

    /**
     * Whether the item can be released. Order: open → fish item → release_only (a species that
     * should have no item, so holding one is abnormal).
     *
     * @param open whether the flag is set
     */
    public static Verdict check(boolean open, boolean isFish, boolean releaseOnly) {
        if (!open) {
            return Verdict.LOCKED;
        }
        if (!isFish) {
            return Verdict.NOT_FISH;
        }
        return releaseOnly ? Verdict.RELEASE_ONLY : Verdict.ALLOWED;
    }

    /** Whether the grade gives reputation and buff (it replaces selling, so no gold). */
    public boolean rewards(Grade grade) {
        return grade.isAtLeast(rewardMinGrade);
    }

    static ReleaseRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("flag", "reward_min_grade", "reputation", "buff"));
        StatKey stat = DEFAULT.buffStat();
        double value = DEFAULT.buffValue();
        int minutes = DEFAULT.buffMinutes();
        Optional<YamlNode> buff = node.get("buff");
        if (buff.isPresent()) {
            YamlNode b = buff.get();
            b.allowOnly(Set.of("stat", "value", "minutes"));
            if (b.has("stat")) {
                try {
                    stat = StatKey.of(b.string("stat"));
                } catch (IllegalArgumentException e) {
                    b.error("stat 이름 형식이 틀렸습니다");
                }
            }
            value = b.number("value", value);
            minutes = b.integer("minutes", minutes, 1, 24 * 60);
        }
        return new ReleaseRules(
                node.string("flag", DEFAULT.flag()),
                node.has("reward_min_grade")
                        ? node.enumValue("reward_min_grade", Grade.class).orElse(Grade.RARE)
                        : Grade.RARE,
                node.integer("reputation", 0, 0, 10_000),
                stat,
                value,
                minutes);
    }
}
