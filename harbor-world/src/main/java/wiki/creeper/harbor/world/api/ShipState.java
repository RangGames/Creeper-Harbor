package wiki.creeper.harbor.world.api;

/** 선박 상태 (ships.state). */
public enum ShipState {
    /** 조선소에 정박 (엔티티 없음). */
    STORED,
    /** 물에 띄움 (엔티티 있음, 한 사람당 하나). */
    AFLOAT,
    /** 파손 (조선소 수리 전까지 띄울 수 없음). */
    WRECKED
}
