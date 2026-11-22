package wiki.creeper.harbor.wildlife.sim;

import java.util.List;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * Custom steering math ('17-3' 9.4 brain approach decision). Pure functions with no server API, so
 * unit tested. Units are blocks, seconds and radians (degrees carry Deg in the name).
 *
 * <ul>
 *   <li>Target following: seek, arrive, flee, wander
 *   <li>School: separation, alignment, cohesion
 *   <li>Medium: probe avoidance (5 points: ahead, up, down, left, right), staying below the
 *       surface, height band, movement that never leaves the medium
 *   <li>Heading: max turn rate limit, direction of travel → Minecraft yaw/pitch
 * </ul>
 */
public final class Steer {

    private static final double EPS = 1e-9;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private Steer() {}

    // ------------------------------------------------------------------ vector helpers

    public static double length(Vec3 v) {
        return Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
    }

    public static double horizontal(Vec3 v) {
        return Math.sqrt(v.x() * v.x() + v.z() * v.z());
    }

    public static Vec3 normalize(Vec3 v) {
        double len = length(v);
        return len < EPS ? Vec3.ZERO : v.mul(1 / len);
    }

    public static Vec3 clampLength(Vec3 v, double max) {
        double len = length(v);
        return len > max && len > EPS ? v.mul(max / len) : v;
    }

    public static double distanceSq(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Angle between two directions (radians). 0 if either is the zero vector. */
    public static double angle(Vec3 a, Vec3 b) {
        Vec3 na = normalize(a);
        Vec3 nb = normalize(b);
        if (na.equals(Vec3.ZERO) || nb.equals(Vec3.ZERO)) {
            return 0;
        }
        return Math.acos(Math.clamp(na.dot(nb), -1.0, 1.0));
    }

    /**
     * Unit vector of the current direction turned toward the desired direction by at most maxAngle
     * (Rodrigues rotation). If exactly opposite, turns about the up axis (horizontal turn).
     */
    public static Vec3 rotateToward(Vec3 current, Vec3 desired, double maxAngle) {
        Vec3 c = normalize(current);
        Vec3 d = normalize(desired);
        if (d.equals(Vec3.ZERO)) {
            return c;
        }
        if (c.equals(Vec3.ZERO)) {
            return d;
        }
        double theta = Math.acos(Math.clamp(c.dot(d), -1.0, 1.0));
        if (theta <= maxAngle) {
            return d;
        }
        Vec3 axis = c.cross(d);
        if (length(axis) < 1e-6) {
            // exactly opposite: up axis so it turns horizontally (X axis if travelling vertically)
            axis = Math.abs(c.y()) < 0.99 ? UP : new Vec3(1, 0, 0);
        }
        Vec3 k = normalize(axis);
        double cos = Math.cos(maxAngle);
        double sin = Math.sin(maxAngle);
        Vec3 rotated = c.mul(cos).add(k.cross(c).mul(sin)).add(k.mul(k.dot(c) * (1 - cos)));
        return normalize(rotated);
    }

    /**
     * Direction of travel → Minecraft yaw (degrees, south +Z is 0, west −X is 90). fallback if
     * there is no horizontal component.
     */
    public static float yawDeg(Vec3 dir, float fallback) {
        if (horizontal(dir) < 1e-6) {
            return fallback;
        }
        return (float) Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
    }

    /**
     * Tilt = atan2(vertical speed, horizontal speed), clamped to ±limit ('17-3' 9.4). Minecraft
     * pitch is positive downward, so the sign is flipped (negative when rising). Model head
     * direction correction is done by Core setHeading.
     */
    public static float pitchDeg(Vec3 velocity, double limitDeg) {
        double h = horizontal(velocity);
        if (h < 1e-6 && Math.abs(velocity.y()) < 1e-6) {
            return 0;
        }
        double deg = Math.toDegrees(Math.atan2(velocity.y(), h));
        return (float) -Math.clamp(deg, -limitDeg, limitDeg);
    }

    // ------------------------------------------------------------------ target following

    public static Vec3 seek(Vec3 pos, Vec3 target, double speed) {
        return normalize(target.sub(pos)).mul(speed);
    }

    /** Slows down near the target (within slowRadius). */
    public static Vec3 arrive(Vec3 pos, Vec3 target, double speed, double slowRadius) {
        Vec3 to = target.sub(pos);
        double d = length(to);
        if (d < EPS) {
            return Vec3.ZERO;
        }
        double s = d < slowRadius ? speed * (d / slowRadius) : speed;
        return to.mul(s / d);
    }

    public static Vec3 flee(Vec3 pos, Vec3 threat, double speed) {
        Vec3 away = pos.sub(threat);
        if (length(away) < EPS) {
            away = new Vec3(1, 0, 0);
        }
        return normalize(away).mul(speed);
    }

    /**
     * Randomly turns the current direction within ±yawJitter horizontally and ±pitchJitter
     * vertically (radians).
     */
    public static Vec3 wander(
            Vec3 heading, double yawJitter, double pitchJitter, RandomGenerator random) {
        Vec3 h = normalize(heading);
        if (h.equals(Vec3.ZERO)) {
            h = new Vec3(0, 0, 1);
        }
        double yaw = Math.atan2(h.x(), h.z()) + (random.nextDouble() * 2 - 1) * yawJitter;
        double pitch =
                Math.clamp(
                        Math.asin(Math.clamp(h.y(), -1.0, 1.0))
                                + (random.nextDouble() * 2 - 1) * pitchJitter,
                        -1.2,
                        1.2);
        double c = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * c, Math.sin(pitch), Math.cos(yaw) * c);
    }

    // ------------------------------------------------------------------ school

    /**
     * Direction away from close neighbours (stronger when closer). Neighbours beyond minDist are
     * ignored.
     */
    public static Vec3 separation(Vec3 pos, List<Vec3> neighbors, double minDist) {
        Vec3 sum = Vec3.ZERO;
        double min2 = minDist * minDist;
        for (Vec3 n : neighbors) {
            Vec3 away = pos.sub(n);
            double d2 = away.dot(away);
            if (d2 < EPS || d2 > min2) {
                continue;
            }
            sum = sum.add(away.mul(1 / d2));
        }
        return normalize(sum);
    }

    /** Average direction of travel of neighbours. */
    public static Vec3 alignment(List<Vec3> neighborVelocities) {
        Vec3 sum = Vec3.ZERO;
        for (Vec3 v : neighborVelocities) {
            sum = sum.add(normalize(v));
        }
        return normalize(sum);
    }

    /** Toward the neighbours' center. */
    public static Vec3 cohesion(Vec3 pos, List<Vec3> neighbors) {
        if (neighbors.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 sum = Vec3.ZERO;
        for (Vec3 n : neighbors) {
            sum = sum.add(n);
        }
        return normalize(sum.mul(1.0 / neighbors.size()).sub(pos));
    }

    // ------------------------------------------------------------------ medium

    /**
     * Medium probe avoidance ('17-3' 9.4: 5 points ahead, up, down, left, right; ahead is speed ×
     * 1.5 seconds away). Returns a vector pushing away from blocked probes. If ahead is blocked,
     * turns sharply toward one of the open sides.
     *
     * @param ahead forward probe distance (blocks)
     * @param side side/up/down probe distance (blocks)
     */
    public static Vec3 avoid(
            MediumField field, Medium medium, Vec3 pos, Vec3 heading, double ahead, double side) {
        Vec3 f = normalize(heading);
        if (f.equals(Vec3.ZERO)) {
            return Vec3.ZERO;
        }
        Vec3 right = normalize(f.cross(UP));
        if (right.equals(Vec3.ZERO)) {
            right = new Vec3(1, 0, 0);
        }
        Vec3 push = Vec3.ZERO;
        boolean frontBlocked =
                !open(field, medium, pos.add(f.mul(ahead)))
                        || !open(field, medium, pos.add(f.mul(Math.min(1.0, ahead))));
        boolean upBlocked = !open(field, medium, pos.add(UP.mul(side)));
        boolean downBlocked = !open(field, medium, pos.sub(UP.mul(side)));
        boolean rightBlocked = !open(field, medium, pos.add(right.mul(side)));
        boolean leftBlocked = !open(field, medium, pos.sub(right.mul(side)));
        if (upBlocked) {
            push = push.sub(UP);
        }
        if (downBlocked) {
            push = push.add(UP);
        }
        if (rightBlocked) {
            push = push.sub(right);
        }
        if (leftBlocked) {
            push = push.add(right);
        }
        if (frontBlocked) {
            // turn toward the open side: check forward diagonal probes in order left, right, up,
            // down
            Vec3[] options = {
                f.add(right).mul(0.5), f.sub(right).mul(0.5), f.add(UP).mul(0.5), f.sub(UP).mul(0.5)
            };
            Vec3 best = null;
            for (Vec3 o : options) {
                if (open(field, medium, pos.add(normalize(o).mul(Math.min(ahead, 2.0))))) {
                    best = o;
                    break;
                }
            }
            push = push.add(best != null ? normalize(best).mul(2.0) : f.mul(-2.0));
        }
        return push;
    }

    public static boolean open(MediumField field, Medium medium, Vec3 p) {
        return field.is(medium, p.x(), p.y(), p.z());
    }

    /**
     * Keeps gap blocks below the surface (water/lava). If the spot gap above is outside the medium,
     * returns a downward push (blocks/second), otherwise 0. 0 for air and floor.
     */
    public static double surfaceKeep(MediumField field, Medium medium, Vec3 pos, double gap) {
        if (medium != Medium.WATER && medium != Medium.LAVA) {
            return 0;
        }
        return open(field, medium, pos.add(new Vec3(0, gap, 0))) ? 0 : -1.0;
    }

    /** Vertical push toward the height band [lo, hi] when outside it (blocks/second). */
    public static double band(double y, double lo, double hi) {
        if (y < lo) {
            return Math.min(1.0, lo - y);
        }
        if (y > hi) {
            return -Math.min(1.0, y - hi);
        }
        return 0;
    }

    /**
     * Surface height (bottom y of the first cell above pos where the medium ends). pos.y + maxScan
     * if not found within maxScan.
     */
    public static double surfaceY(MediumField field, Medium medium, Vec3 pos, int maxScan) {
        int x = MediumField.floor(pos.x());
        int z = MediumField.floor(pos.z());
        int y0 = MediumField.floor(pos.y());
        for (int y = y0; y <= y0 + maxScan; y++) {
            if (!field.is(medium, x, y, z)) {
                return y;
            }
        }
        return y0 + maxScan;
    }

    /** Resulting position, and whether the original move was blocked. */
    public record Move(Vec3 pos, boolean blocked) {}

    /**
     * Moves only within the medium ('17-3' 9.6 lava/air species never leave their medium). If the
     * full move is blocked, slides per axis (horizontal → vertical); if still blocked, stays in
     * place.
     */
    public static Move move(MediumField field, Medium medium, Vec3 pos, Vec3 velocity, double dt) {
        Vec3 delta = velocity.mul(dt);
        Vec3 full = pos.add(delta);
        if (open(field, medium, full)) {
            return new Move(full, false);
        }
        Vec3[] slides = {
            pos.add(new Vec3(delta.x(), 0, delta.z())),
            pos.add(new Vec3(delta.x(), 0, 0)),
            pos.add(new Vec3(0, 0, delta.z())),
            pos.add(new Vec3(0, delta.y(), 0))
        };
        for (Vec3 s : slides) {
            if (!s.equals(pos) && open(field, medium, s)) {
                return new Move(s, true);
            }
        }
        return new Move(pos, true);
    }
}
