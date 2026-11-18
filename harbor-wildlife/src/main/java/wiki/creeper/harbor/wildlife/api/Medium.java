package wiki.creeper.harbor.wildlife.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 야생 개체가 움직이는 매질 ('17-3' 9.4 매질별 AI). */
public enum Medium implements Keyed {
    /** 물 (기본). 수면·바닥 사이 depth 범위 안. */
    WATER,
    /** 용암 (용암 호수). 용암 칸만 이동 가능. */
    LAVA,
    /** 공중 (공허의 바다). 지역 경계와 높이 띠 안의 공기 칸, 중력 없음. */
    AIR,
    /** 바닥 (조개류). 해저 모래·자갈 위에 고정, 이동 없음. */
    FLOOR;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** 이 매질 개체가 실제로 차지하는 칸의 종류 (바닥 개체는 물 칸에 있다). */
    public Medium cell() {
        return this == FLOOR ? WATER : this;
    }
}
