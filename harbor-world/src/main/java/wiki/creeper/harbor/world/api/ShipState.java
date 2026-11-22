package wiki.creeper.harbor.world.api;

/** Ship state (ships.state). */
public enum ShipState {
    /** Docked at the shipyard (no entity). */
    STORED,
    /** Afloat on water (entity exists, one per person). */
    AFLOAT,
    /** Wrecked (cannot be launched until repaired at the shipyard). */
    WRECKED
}
