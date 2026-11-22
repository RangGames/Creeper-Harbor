package wiki.creeper.harbor.common.rig;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Interpolation between keyframes. The easing written on a key applies to the interval from that
 * key to the next.
 */
public enum Easing implements Keyed {
    LINEAR,
    /** Holds the value until the next key, then jumps. */
    STEP,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    /**
     * Smoothly connects using the surrounding keys too (position, scale and Euler angles alike).
     */
    CATMULLROM;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /**
     * Maps interval progress f (0~1) onto the curve. CATMULLROM is computed separately in {@link
     * Track}, so it is returned as-is.
     */
    public double apply(double f) {
        return switch (this) {
            case LINEAR, CATMULLROM -> f;
            case STEP -> 0;
            case EASE_IN -> f * f * f;
            case EASE_OUT -> 1 - Math.pow(1 - f, 3);
            case EASE_IN_OUT -> f < 0.5 ? 4 * f * f * f : 1 - Math.pow(-2 * f + 2, 3) / 2;
        };
    }
}
