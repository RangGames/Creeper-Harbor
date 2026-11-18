package wiki.creeper.harbor.wildlife.content;

import wiki.creeper.harbor.common.model.Keyed;

/** 성향 ('17-3' 9.3, 시제품 GROUPS). 어종의 고유 동작으로 정하고 species.yml에서 덮어쓸 수 있다. */
public enum WildGroup implements Keyed {
    /** 돌진·도망·도약형. */
    ACTIVE,
    NORMAL,
    /** 매복·일광욕·응시형. */
    LAZY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
