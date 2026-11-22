package wiki.creeper.harbor.wildlife.sim;

import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * Medium lookup ('17-3' 9.4 medium grid). Returns water/lava/air bits per cell. Unloaded cells or
 * cells without a grid are 0 (treated as blocked). The simulation thread reads without locking, so
 * implementations must read only immutable data.
 */
@FunctionalInterface
public interface MediumField {

    int WATER = 1;
    int LAVA = 2;
    int AIR = 4;

    /** Empty field (every cell blocked). */
    MediumField EMPTY = (x, y, z) -> 0;

    int cell(int x, int y, int z);

    static int bit(Medium medium) {
        return switch (medium.cell()) {
            case LAVA -> LAVA;
            case AIR -> AIR;
            default -> WATER;
        };
    }

    default boolean is(Medium medium, int x, int y, int z) {
        return (cell(x, y, z) & bit(medium)) != 0;
    }

    default boolean is(Medium medium, double x, double y, double z) {
        return is(medium, floor(x), floor(y), floor(z));
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }
}
