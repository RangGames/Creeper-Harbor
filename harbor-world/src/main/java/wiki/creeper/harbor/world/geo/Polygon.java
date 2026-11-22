package wiki.creeper.harbor.world.geo;

import java.util.List;
import wiki.creeper.harbor.common.content.type.RegionDef;

/**
 * Horizontal polygon (x, z). Used for region bounds and sub-areas. Has no server API, so unit tests
 * and harbor-tools use it too. Immutable.
 *
 * <p>Point tests use the even-odd rule (same formula as RegionDef.contains). Chunk classification
 * has three outcomes, "fully inside · fully outside · straddling", so only chunks on the boundary
 * need a precise test (docs 20 8.4 'region detection').
 */
public final class Polygon {

    /** Relation between a chunk (square) and the polygon. */
    public enum Cover {
        INSIDE,
        OUTSIDE,
        PARTIAL
    }

    private final double[] xs;
    private final double[] zs;
    private final double minX;
    private final double minZ;
    private final double maxX;
    private final double maxZ;

    public Polygon(List<RegionDef.Point> points) {
        int n = points.size();
        xs = new double[n];
        zs = new double[n];
        double x0 = Double.POSITIVE_INFINITY;
        double z0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double z1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            RegionDef.Point p = points.get(i);
            xs[i] = p.x();
            zs[i] = p.z();
            x0 = Math.min(x0, p.x());
            z0 = Math.min(z0, p.z());
            x1 = Math.max(x1, p.x());
            z1 = Math.max(z1, p.z());
        }
        if (n == 0) {
            x0 = z0 = x1 = z1 = 0;
        }
        minX = x0;
        minZ = z0;
        maxX = x1;
        maxZ = z1;
    }

    public int size() {
        return xs.length;
    }

    public double minX() {
        return minX;
    }

    public double minZ() {
        return minZ;
    }

    public double maxX() {
        return maxX;
    }

    public double maxZ() {
        return maxZ;
    }

    /** Whether the point is inside (even-odd rule). Always outside with fewer than 3 vertices. */
    public boolean contains(double x, double z) {
        int n = xs.length;
        if (n < 3 || x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((zs[i] > z) != (zs[j] > z)
                    && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Distance from the point to the nearest edge. */
    public double edgeDistance(double x, double z) {
        int n = xs.length;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            best = Math.min(best, segmentDistance(x, z, xs[j], zs[j], xs[i], zs[i]));
        }
        return best;
    }

    /**
     * Nearest point [x, z] on the boundary to the given point. Used to choose the push-back
     * direction (outward from the boundary). The input point if there are no vertices.
     */
    public double[] nearestEdgePoint(double x, double z) {
        int n = xs.length;
        double best = Double.POSITIVE_INFINITY;
        double[] result = {x, z};
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double[] p = projectOnSegment(x, z, xs[j], zs[j], xs[i], zs[i]);
            double d = Math.hypot(p[0] - x, p[1] - z);
            if (d < best) {
                best = d;
                result = p;
            }
        }
        return result;
    }

    /**
     * Relation to the axis-aligned square [x0, x0+size) × [z0, z0+size). INSIDE if all four corners
     * are inside and no edge crosses the square, OUTSIDE if all are outside and no edge or vertex
     * touches the square, otherwise PARTIAL. Ambiguous cases are PARTIAL, so the result is always
     * safe.
     */
    public Cover cover(double x0, double z0, double size) {
        double x1 = x0 + size;
        double z1 = z0 + size;
        if (x1 < minX || x0 > maxX || z1 < minZ || z0 > maxZ) {
            return Cover.OUTSIDE;
        }
        boolean c00 = contains(x0, z0);
        boolean c10 = contains(x1, z0);
        boolean c01 = contains(x0, z1);
        boolean c11 = contains(x1, z1);
        boolean crosses = crossesSquare(x0, z0, x1, z1);
        if (c00 && c10 && c01 && c11 && !crosses) {
            return Cover.INSIDE;
        }
        if (!c00 && !c10 && !c01 && !c11 && !crosses) {
            return Cover.OUTSIDE;
        }
        return Cover.PARTIAL;
    }

    /** Whether any edge passes through the square or any vertex lies inside it. */
    private boolean crossesSquare(double x0, double z0, double x1, double z1) {
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = xs[j];
            double az = zs[j];
            double bx = xs[i];
            double bz = zs[i];
            if (ax >= x0 && ax <= x1 && az >= z0 && az <= z1) {
                return true;
            }
            if (segmentsIntersect(ax, az, bx, bz, x0, z0, x1, z0)
                    || segmentsIntersect(ax, az, bx, bz, x1, z0, x1, z1)
                    || segmentsIntersect(ax, az, bx, bz, x1, z1, x0, z1)
                    || segmentsIntersect(ax, az, bx, bz, x0, z1, x0, z0)) {
                return true;
            }
        }
        return false;
    }

    static boolean segmentsIntersect(
            double ax,
            double az,
            double bx,
            double bz,
            double cx,
            double cz,
            double dx,
            double dz) {
        double d1 = cross(cx, cz, dx, dz, ax, az);
        double d2 = cross(cx, cz, dx, dz, bx, bz);
        double d3 = cross(ax, az, bx, bz, cx, cz);
        double d4 = cross(ax, az, bx, bz, dx, dz);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && onSegment(cx, cz, dx, dz, ax, az))
                || (d2 == 0 && onSegment(cx, cz, dx, dz, bx, bz))
                || (d3 == 0 && onSegment(ax, az, bx, bz, cx, cz))
                || (d4 == 0 && onSegment(ax, az, bx, bz, dx, dz));
    }

    private static double cross(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    private static boolean onSegment(
            double ax, double az, double bx, double bz, double px, double pz) {
        return px >= Math.min(ax, bx)
                && px <= Math.max(ax, bx)
                && pz >= Math.min(az, bz)
                && pz <= Math.max(az, bz);
    }

    private static double segmentDistance(
            double px, double pz, double ax, double az, double bx, double bz) {
        double[] p = projectOnSegment(px, pz, ax, az, bx, bz);
        return Math.hypot(px - p[0], pz - p[1]);
    }

    private static double[] projectOnSegment(
            double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len;
        t = Math.max(0, Math.min(1, t));
        return new double[] {ax + t * dx, az + t * dz};
    }
}
