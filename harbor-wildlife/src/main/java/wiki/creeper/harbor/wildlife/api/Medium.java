package wiki.creeper.harbor.wildlife.api;

import wiki.creeper.harbor.common.model.Keyed;

/** The medium a wild fish moves through ('17-3' 9.4 per-medium AI). */
public enum Medium implements Keyed {
    /** Water (default). Within the depth range between surface and floor. */
    WATER,
    /** Lava (lava lakes). Can move only through lava cells. */
    LAVA,
    /** Air (Void Sea). Air cells within the region bounds and height band, no gravity. */
    AIR,
    /** Floor (shellfish). Fixed on seabed sand/gravel, does not move. */
    FLOOR;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** The kind of cell a fish of this medium actually occupies (floor fish sit in water cells). */
    public Medium cell() {
        return this == FLOOR ? WATER : this;
    }
}
