package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 해상 이벤트 종류 (world/events.yml type). */
public enum SeaEventType implements Keyed {
    /** 폭풍: 개인 날씨, 선박 내구도 감소, 낙뢰. */
    STORM,
    /** 유령선 습격: 해골 해적 웨이브, 처치 후 유령선 보물. */
    GHOST_SHIP,
    /** 물고기 대이동: 대상 어종 ×3, 신화 '대이동의 인도자'. */
    MIGRATION,
    /** 주간 낚시 대회 (순위는 Season RankingService). */
    TOURNAMENT,
    /** 그 밖의 이벤트 (첫 물고기의 귀환 등, fish_effects만). */
    SPECIAL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
