package wiki.creeper.harbor.wildlife.sim;

/**
 * Ray ↔ sphere test ('17-3' 9.2 raycast: harpoon throws and boss cinematics check wild fish hits).
 * Pure math.
 */
public final class Raycast {

    private Raycast() {}

    /**
     * The t at which ray o + t·d (d a unit vector) first touches sphere (c, r). 0 if the origin is
     * inside the sphere. −1 on a miss or if farther than maxDistance.
     */
    public static double sphere(
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double cx,
            double cy,
            double cz,
            double r,
            double maxDistance) {
        double lx = cx - ox;
        double ly = cy - oy;
        double lz = cz - oz;
        double c = lx * lx + ly * ly + lz * lz - r * r;
        if (c <= 0) {
            return 0;
        }
        double b = lx * dx + ly * dy + lz * dz;
        if (b < 0) {
            return -1;
        }
        double disc = b * b - c;
        if (disc < 0) {
            return -1;
        }
        double t = b - Math.sqrt(disc);
        return t <= maxDistance ? t : -1;
    }
}
