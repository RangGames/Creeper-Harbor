package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * Natural spawn spot ('17-3' 9.4): a random point in a 16~40 block ring around the player, checks
 * medium depth (water/lava at least 2 cells, 3 free cells for air), and at least 8 blocks from
 * other school centers. Pure math.
 */
public final class SpawnPlanner {

    /**
     * A found spot.
     *
     * @param depth depth below the surface (0 for air)
     * @param floorY floor of that column (y just above where the medium ends; floor fish spot)
     */
    public record Spot(Vec3 pos, Medium medium, double depth, int floorY) {}

    private SpawnPlanner() {}

    /** Random point in the ring (uniform by area). */
    public static double[] ringPoint(
            double cx, double cz, double rMin, double rMax, RandomGenerator random) {
        double a = random.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(rMin * rMin + random.nextDouble() * (rMax * rMax - rMin * rMin));
        return new double[] {cx + Math.cos(a) * r, cz + Math.sin(a) * r};
    }

    /**
     * Walks one column (x, z) down from yTop to yBottom to find medium runs, and picks a random
     * height within a run that meets the conditions.
     *
     * @param minDepth minimum thickness of a water/lava run (cells)
     * @param clearance minimum thickness of an air run (cells)
     */
    public static Optional<Spot> column(
            MediumField field,
            Medium medium,
            int x,
            int z,
            int yTop,
            int yBottom,
            int minDepth,
            int clearance,
            RandomGenerator random) {
        Medium cellMedium = medium.cell();
        int need = cellMedium == Medium.AIR ? clearance : minDepth;
        record Segment(int top, int bottom) {}
        List<Segment> segments = new ArrayList<>();
        int y = yTop;
        while (y >= yBottom) {
            if (!field.is(cellMedium, x, y, z)) {
                y--;
                continue;
            }
            int top = y;
            while (y >= yBottom && field.is(cellMedium, x, y, z)) {
                y--;
            }
            int bottom = y + 1;
            if (top - bottom + 1 >= need) {
                segments.add(new Segment(top, bottom));
            }
        }
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        Segment s = segments.get(random.nextInt(segments.size()));
        // surface: if the cell above the run's top is not medium, its bottom is the surface (if cut
        // off at yTop, look further up)
        int surface = s.top() + 1;
        while (surface <= yTop + 32 && field.is(cellMedium, x, surface, z)) {
            surface++;
        }
        if (medium == Medium.FLOOR) {
            return Optional.of(
                    new Spot(
                            new Vec3(x + 0.5, s.bottom() + 0.15, z + 0.5),
                            medium,
                            surface - s.bottom(),
                            s.bottom()));
        }
        double py;
        if (cellMedium == Medium.AIR) {
            py = s.bottom() + 1 + random.nextDouble() * Math.max(0, s.top() - s.bottom() - 1);
        } else {
            // avoid the cell right below the surface (stay 0.5 blocks under the surface)
            int hi = Math.max(s.bottom(), s.top() - 1);
            py = s.bottom() + 0.5 + random.nextDouble() * Math.max(0, hi - s.bottom());
        }
        double depth = cellMedium == Medium.AIR ? 0 : surface - py;
        return Optional.of(new Spot(new Vec3(x + 0.5, py, z + 0.5), medium, depth, s.bottom()));
    }

    /** Whether it is at least gap away from other school centers. */
    public static boolean farFrom(Vec3 p, List<Vec3> centers, double gap) {
        double g2 = gap * gap;
        for (Vec3 c : centers) {
            if (Steer.distanceSq(p, c) < g2) {
                return false;
            }
        }
        return true;
    }

    public static int schoolSize(int min, int max, RandomGenerator random) {
        int lo = Math.max(1, min);
        int hi = Math.max(lo, max);
        return lo + random.nextInt(hi - lo + 1);
    }
}
