package wiki.creeper.harbor.common.rig;

/**
 * Unit quaternion rotation (immutable). Component order is (x, y, z, w), same as JOML and
 * Minecraft.
 *
 * <p>Euler angles are [x, y, z] in degrees, applied X first, then Y, then Z (q = qZ · qY · qX).
 */
public record Quat(double x, double y, double z, double w) {

    public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

    /**
     * Rotation that undoes item_display drawing items rotated 180° around Y ('16. Plugin design'
     * 5.10, prototype right_rotation [0, 1, 0, 0]).
     */
    public static final Quat ITEM_FLIP = new Quat(0, 1, 0, 0);

    public static Quat axisAngle(Vec3 axis, double degrees) {
        double len = axis.length();
        if (len == 0) {
            return IDENTITY;
        }
        double half = Math.toRadians(degrees) / 2;
        double s = Math.sin(half) / len;
        return new Quat(axis.x() * s, axis.y() * s, axis.z() * s, Math.cos(half));
    }

    public static Quat rotX(double degrees) {
        double half = Math.toRadians(degrees) / 2;
        return new Quat(Math.sin(half), 0, 0, Math.cos(half));
    }

    public static Quat rotY(double degrees) {
        double half = Math.toRadians(degrees) / 2;
        return new Quat(0, Math.sin(half), 0, Math.cos(half));
    }

    public static Quat rotZ(double degrees) {
        double half = Math.toRadians(degrees) / 2;
        return new Quat(0, 0, Math.sin(half), Math.cos(half));
    }

    /** Euler angles (degrees, applied X → Y → Z). */
    public static Quat euler(Vec3 degrees) {
        return rotZ(degrees.z()).mul(rotY(degrees.y())).mul(rotX(degrees.x()));
    }

    /**
     * Inverse of {@link #euler}: Euler angles that produce this rotation (degrees, X → Y → Z
     * order). y is −90~90; when y is near ±90° (gimbal lock) x is set to 0. The other solution for
     * the same rotation ({@code [x+180, 180−y, z+180]}) is picked by {@link #eulerNear}.
     */
    public Vec3 toEuler() {
        Quat q = normalize();
        double r20 = 2 * (q.x * q.z - q.w * q.y);
        double sinY = Math.max(-1, Math.min(1, -r20));
        double ey = Math.toDegrees(Math.asin(sinY));
        double ex;
        double ez;
        if (Math.abs(sinY) > 0.999999) {
            ex = 0;
            double r01 = 2 * (q.x * q.y - q.w * q.z);
            double r11 = 1 - 2 * (q.x * q.x + q.z * q.z);
            ez = Math.toDegrees(Math.atan2(-r01, r11));
        } else {
            double r21 = 2 * (q.y * q.z + q.w * q.x);
            double r22 = 1 - 2 * (q.x * q.x + q.y * q.y);
            double r10 = 2 * (q.x * q.y + q.w * q.z);
            double r00 = 1 - 2 * (q.y * q.y + q.z * q.z);
            ex = Math.toDegrees(Math.atan2(r21, r22));
            ez = Math.toDegrees(Math.atan2(r10, r00));
        }
        return new Vec3(ex, ey, ez);
    }

    /**
     * The Euler angles producing this rotation that are closest to previous (degrees). Considers
     * both solutions and ±360° per component. Used when chaining angles extracted per frame (so
     * linear interpolation does not spin the wrong way).
     */
    public Vec3 eulerNear(Vec3 previous) {
        Vec3 a = toEuler();
        Vec3 b = new Vec3(a.x() + 180, 180 - a.y(), a.z() + 180);
        Vec3 na = unwrap(a, previous);
        Vec3 nb = unwrap(b, previous);
        return na.distance(previous) <= nb.distance(previous) ? na : nb;
    }

    private static Vec3 unwrap(Vec3 v, Vec3 near) {
        return new Vec3(unwrap(v.x(), near.x()), unwrap(v.y(), near.y()), unwrap(v.z(), near.z()));
    }

    private static double unwrap(double angle, double near) {
        return angle + 360 * Math.round((near - angle) / 360);
    }

    /**
     * Entity orientation (Minecraft yaw/pitch, degrees). Same rotation Display entities use when
     * rendering (rotationYXZ(−yaw, pitch, 0)). The model's +Z faces the yaw direction, and positive
     * pitch looks down.
     */
    public static Quat entity(double yaw, double pitch) {
        return rotY(-yaw).mul(rotX(pitch));
    }

    /** Hamilton product this · o (o applied first). */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    public Quat conjugate() {
        return new Quat(-x, -y, -z, w);
    }

    public double dot(Quat o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    public Quat normalize() {
        double len = Math.sqrt(dot(this));
        if (len == 0) {
            return IDENTITY;
        }
        return new Quat(x / len, y / len, z / len, w / len);
    }

    /** Rotates a vector. */
    public Vec3 rotate(Vec3 v) {
        // v' = v + 2w(u×v) + 2u×(u×v)
        Vec3 u = new Vec3(x, y, z);
        Vec3 t = u.cross(v).mul(2);
        return v.add(t.mul(w)).add(u.cross(t));
    }

    /** Angle between two rotations (degrees, 0~180). q and −q are treated as the same rotation. */
    public double angleTo(Quat o) {
        double d = Math.min(1, Math.abs(dot(o)));
        return Math.toDegrees(2 * Math.acos(d));
    }

    /**
     * Spherical linear interpolation (the shorter way). Same as how the client interpolates Display
     * rotations.
     */
    public static Quat slerp(Quat a, Quat b, double f) {
        double cos = a.dot(b);
        Quat end = b;
        if (cos < 0) {
            cos = -cos;
            end = new Quat(-b.x, -b.y, -b.z, -b.w);
        }
        if (cos > 0.9995) {
            return new Quat(
                            a.x + (end.x - a.x) * f,
                            a.y + (end.y - a.y) * f,
                            a.z + (end.z - a.z) * f,
                            a.w + (end.w - a.w) * f)
                    .normalize();
        }
        double theta = Math.acos(cos);
        double sin = Math.sin(theta);
        double wa = Math.sin((1 - f) * theta) / sin;
        double wb = Math.sin(f * theta) / sin;
        return new Quat(
                a.x * wa + end.x * wb,
                a.y * wa + end.y * wb,
                a.z * wa + end.z * wb,
                a.w * wa + end.w * wb);
    }
}
