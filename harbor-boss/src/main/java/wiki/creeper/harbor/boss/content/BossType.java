package wiki.creeper.harbor.boss.content;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * 보스 유형 ('7. 밸런스 기획' 9장). 인원당 체력 증가·제한 시간·주간 횟수·클리어 골드·예고 시간 기본값은 balance.yml {@code
 * boss.types.<유형>}에서 가져온다 ({@link BossBalance}).
 */
public enum BossType implements Keyed {
    /** 입문 보스 (그놈). */
    INTRO,
    /** 해역 보스 (곰치·철갑상어·대왕오징어). */
    SEA,
    /** 차원 보스 (불꽃왕 잉어·공허 성운어). */
    DIMENSION,
    /** 시즌 레이드 (해구의 군주 '군주의 분노'). */
    RAID,
    /** 월드 보스 (녹빛 폭뢰어). */
    WORLD;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
