package wiki.creeper.harbor.collection.content;

import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.core.api.StatKey;

/**
 * 방류 규칙 (release.yml, 추가 제안, docs 20 4.4 '방류'). 수치는 제안값.
 *
 * @param flag 이 서버 플래그가 서야 방류가 열린다 ('5. 서버 스토리' 6장 클라이맥스 이후)
 * @param rewardMinGrade 이 등급 이상만 평판·행운 버프
 * @param reputation 평판 (0이면 NPC balance.yml reputation.sources.release 기본값)
 * @param buffStat 행운 버프 스탯 (size_luck)
 * @param buffValue 버프 값 (ADD)
 * @param buffMinutes 버프 시간 (다시 방류하면 시간만 새로 시작, 겹치지 않음)
 */
public record ReleaseRules(
        String flag,
        Grade rewardMinGrade,
        int reputation,
        StatKey buffStat,
        double buffValue,
        int buffMinutes) {

    /** 판정 결과. */
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
     * 방류할 수 있는지. 순서: 개방 여부 → 어종 아이템 → release_only(아이템이 없어야 하는 어종이므로 들고 있으면 비정상).
     *
     * @param open 플래그가 섰는지
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

    /** 평판·버프를 주는 등급인지 (판매 대신이라 골드는 없다). */
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
