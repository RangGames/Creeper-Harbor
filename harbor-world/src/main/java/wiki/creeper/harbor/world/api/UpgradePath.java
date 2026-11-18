package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 선박 업그레이드 갈래 (속도·적재·선체 × 3단계, docs 20 8.4). */
public enum UpgradePath implements Keyed {
    SPEED,
    CARGO,
    HULL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
