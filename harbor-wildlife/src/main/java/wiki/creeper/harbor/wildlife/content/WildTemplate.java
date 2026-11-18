package wiki.creeper.harbor.wildlife.content;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * 리그 템플릿 ('17-3' 9.3 분포: fish 128, ceph 6, ray 6, seahorse 2, jelly 2, clam 1). 최대 회전 속도·기울기 한계의
 * 기본값을 고르고, 어종 리그가 없을 때 rig/wild/&lt;템플릿&gt; 리그로 대신 그린다.
 */
public enum WildTemplate implements Keyed {
    FISH,
    CEPH,
    RAY,
    SEAHORSE,
    JELLY,
    CLAM;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
