package wiki.creeper.harbor.common.rig;

/**
 * 단위 사원수 회전 (변경 불가). 성분 순서는 JOML·마인크래프트와 같은 (x, y, z, w).
 *
 * <p>오일러 각은 도 단위 [x, y, z]이고 X축을 먼저, 그다음 Y, Z 순서로 돌린다 (q = qZ · qY · qX).
 */
public record Quat(double x, double y, double z, double w) {

    public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

    /**
     * item_display가 아이템을 Y축으로 180° 돌려 그리는 것을 되돌리는 회전 ('16. 플러그인 설계' 5.10, 시제품 right_rotation [0, 1,
     * 0, 0]).
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

    /** 오일러 각 (도, X → Y → Z 순서로 적용). */
    public static Quat euler(Vec3 degrees) {
        return rotZ(degrees.z()).mul(rotY(degrees.y())).mul(rotX(degrees.x()));
    }

    /**
     * {@link #euler}의 역: 이 회전을 만드는 오일러 각 (도, X → Y → Z 순서). y는 −90~90이고, y가 ±90°에 가까우면(짐벌 잠금) x를
     * 0으로 둔다. 같은 회전을 만드는 다른 해({@code [x+180, 180−y, z+180]})는 {@link #eulerNear}로 고른다.
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
     * 이 회전을 만드는 오일러 각 중 previous에 가장 가까운 것 (도). 두 해와 성분별 ±360°를 모두 따져 고른다. 프레임마다 뽑은 각을 이어 붙일 때 (선형
     * 보간이 반대쪽으로 돌지 않게) 쓴다.
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
     * 엔티티 방향 (마인크래프트 yaw·pitch, 도). Display 엔티티가 그릴 때 쓰는 회전과 같다 (rotationYXZ(−yaw, pitch, 0)). 모델의
     * +Z가 yaw 방향을 향하고, pitch가 양수면 아래를 본다.
     */
    public static Quat entity(double yaw, double pitch) {
        return rotY(-yaw).mul(rotX(pitch));
    }

    /** 해밀턴 곱 this · o (o를 먼저 적용). */
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

    /** 벡터를 돌린다. */
    public Vec3 rotate(Vec3 v) {
        // v' = v + 2w(u×v) + 2u×(u×v)
        Vec3 u = new Vec3(x, y, z);
        Vec3 t = u.cross(v).mul(2);
        return v.add(t.mul(w)).add(u.cross(t));
    }

    /** 두 회전 사이의 각도 (도, 0~180). q와 −q는 같은 회전으로 본다. */
    public double angleTo(Quat o) {
        double d = Math.min(1, Math.abs(dot(o)));
        return Math.toDegrees(2 * Math.acos(d));
    }

    /** 구면 선형 보간 (가까운 쪽으로). 클라이언트가 Display 회전을 보간하는 방식과 같다. */
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
