package wiki.creeper.harbor.wildlife.sim;

/** Threat detection rules ('17-3' 9.4 threat detection, fleeing). Pure math. */
public final class ThreatRules {

    private ThreatRules() {}

    /**
     * Effective detection radius. ×sneak when sneaking, ×boat when in a boat, ×habituate if
     * recently startled by the same player.
     */
    public static double radius(
            double base,
            boolean sneaking,
            boolean boat,
            boolean habituated,
            double sneakFactor,
            double boatFactor,
            double habituateFactor) {
        double r = base;
        if (sneaking) {
            r *= sneakFactor;
        }
        if (boat) {
            r *= boatFactor;
        }
        if (habituated) {
            r *= habituateFactor;
        }
        return r;
    }

    /**
     * Whether it triggers: within the radius, the approach speed toward the fish is at or above the
     * threshold, or it comes within innerRatio of the radius.
     *
     * @param closing approach speed toward the fish (blocks/second, negative when moving away)
     */
    public static boolean triggered(
            double distance,
            double closing,
            double radius,
            double closingThreshold,
            double innerRatio) {
        if (radius <= 0 || distance > radius) {
            return false;
        }
        return closing >= closingThreshold || distance <= radius * innerRatio;
    }

    /** Approach speed = player velocity projected onto the (fish − player) direction. */
    public static double closing(
            double px,
            double py,
            double pz,
            double vx,
            double vy,
            double vz,
            double fx,
            double fy,
            double fz) {
        double dx = fx - px;
        double dy = fy - py;
        double dz = fz - pz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-9) {
            return 0;
        }
        return (vx * dx + vy * dy + vz * dz) / d;
    }
}
