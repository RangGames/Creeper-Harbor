package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * 평판 출처 (docs 20 5.4 평판 표). 출처별 기본값·하루 상한은 balance.yml {@code reputation.sources.<키>} · {@code
 * reputation.daily_caps.<키>}. 보정자 reputation_gain(경제 계열 '항구 투자')은 {@link #contribution()}인 출처에만
 * 곱한다.
 */
public enum ReputationSource implements Keyed {
    MAIN_STORY(false),
    REGION_QUEST(false),
    DAILY_REQUEST(false),
    WEEKLY_REQUEST(false),
    /** 수집 의뢰 (CONTRIBUTION). */
    COLLECT_REQUEST(true),
    BOSS_FIRST_KILL(false),
    WORLD_BOSS(false),
    /** 방류, 희귀 이상 (CONTRIBUTION). 하루 상한 30. */
    RELEASE(true),
    /** 관리자 명령 (원장 대신 admin_log). 상한·보정자 없음. */
    ADMIN(false),
    OTHER(false);

    private final boolean contribution;

    ReputationSource(boolean contribution) {
        this.contribution = contribution;
    }

    /** 항구 기여(CONTRIBUTION) 출처인지. */
    public boolean contribution() {
        return contribution;
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
