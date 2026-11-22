package wiki.creeper.harbor.common.rig;

/** 3D vector (immutable). For rig math only, so it does not rely on the server API (JOML). */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE = new Vec3(1, 1, 1);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 mul(double k) {
        return new Vec3(x * k, y * k, z * k);
    }

    /** Component-wise product (applies per-axis scale). */
    public Vec3 mul(Vec3 o) {
        return new Vec3(x * o.x, y * o.y, z * o.z);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public double distance(Vec3 o) {
        return sub(o).length();
    }

    /** Largest component difference (for size comparison). */
    public double maxDiff(Vec3 o) {
        return Math.max(Math.abs(x - o.x), Math.max(Math.abs(y - o.y), Math.abs(z - o.z)));
    }

    public static Vec3 lerp(Vec3 a, Vec3 b, double f) {
        return new Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
    }

    /** Uniform Catmull-Rom spline. The point at f (0~1) on the p1→p2 segment. */
    public static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double f) {
        double f2 = f * f;
        double f3 = f2 * f;
        return new Vec3(
                cr(p0.x, p1.x, p2.x, p3.x, f, f2, f3),
                cr(p0.y, p1.y, p2.y, p3.y, f, f2, f3),
                cr(p0.z, p1.z, p2.z, p3.z, f, f2, f3));
    }

    private static double cr(
            double p0, double p1, double p2, double p3, double f, double f2, double f3) {
        return 0.5
                * ((2 * p1)
                        + (-p0 + p2) * f
                        + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2
                        + (-p0 + 3 * p1 - 3 * p2 + p3) * f3);
    }
}
