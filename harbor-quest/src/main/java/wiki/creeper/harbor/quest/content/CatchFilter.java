package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** catch 목표가 세는 포획 출처 (추가 제안 objectives[].source). */
public enum CatchFilter implements Keyed {
    /** 낚싯대 포획만 (보스 확정 포획 포함). 기본값. */
    ROD,
    /** 통발 수확도 센다. */
    ANY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
