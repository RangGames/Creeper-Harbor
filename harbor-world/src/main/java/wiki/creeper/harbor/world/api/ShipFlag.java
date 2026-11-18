package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 선박 능력 (world/ships.yml flags). */
public enum ShipFlag implements Keyed {
    /** 쇄빙: 서리 협만 얼음 구역에서 앞쪽 얼음을 깬다. */
    ICE_BREAK,
    /** 폭풍 해역 진입. */
    STORM,
    /** 원양 항해. */
    OCEAN;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
