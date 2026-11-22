package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** Ship ability (world/ships.yml flags). */
public enum ShipFlag implements Keyed {
    /** Icebreaking: breaks the ice ahead in the Frost Fjord ice zone. */
    ICE_BREAK,
    /** Entering storm waters. */
    STORM,
    /** Ocean voyages. */
    OCEAN;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
