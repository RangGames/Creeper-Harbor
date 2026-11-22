package wiki.creeper.harbor.world.housing;

/**
 * Housing plot cell layout (docs 20 8.4 'plot layout'). Assigns cell×cell cells (32 max size + 8
 * road, proposed value) in the harbor_homes world in spiral order from the origin. Plot index n and
 * cell (gx, gz) convert both ways. A plot sits size×size at the center of its cell and grows around
 * the center. No server API (unit tests).
 */
public final class PlotGrid {

    private PlotGrid() {}

    /** Cell coordinates. */
    public record Cell(int x, int z) {}

    /** Plot bounds (blocks, both ends inclusive). */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {

        public boolean contains(double x, double z) {
            return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
        }

        public double centerX() {
            return (minX + maxX + 1) / 2.0;
        }

        public double centerZ() {
            return (minZ + maxZ + 1) / 2.0;
        }
    }

    /** The n-th cell (0 = origin, then a counter-clockwise spiral starting to the right). */
    public static Cell cell(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("플롯 번호는 0 이상입니다: " + n);
        }
        if (n == 0) {
            return new Cell(0, 0);
        }
        // Ring k: 8k cells, starting index (2k-1)^2
        long k = (long) Math.ceil((Math.sqrt((double) (n + 1)) - 1) / 2);
        long side = 2 * k;
        long start = (2 * k - 1) * (2 * k - 1);
        long t = n - start;
        long x;
        long z;
        if (t < side) { // Right side, bottom to top
            x = k;
            z = -k + 1 + t;
        } else if (t < 2 * side) { // Top side, right to left
            x = k - 1 - (t - side);
            z = k;
        } else if (t < 3 * side) { // Left side, top to bottom
            x = -k;
            z = k - 1 - (t - 2 * side);
        } else { // Bottom side, left to right
            x = -k + 1 + (t - 3 * side);
            z = -k;
        }
        return new Cell((int) x, (int) z);
    }

    /** Cell → plot index (inverse of {@link #cell}). */
    public static long index(int x, int z) {
        if (x == 0 && z == 0) {
            return 0;
        }
        long k = Math.max(Math.abs(x), Math.abs(z));
        long side = 2 * k;
        long start = (2 * k - 1) * (2 * k - 1);
        if (x == k && z > -k) {
            return start + (z + k - 1);
        }
        if (z == k) {
            return start + side + (k - 1 - x);
        }
        if (x == -k) {
            return start + 2 * side + (k - 1 - z);
        }
        return start + 3 * side + (x + k - 1);
    }

    /** Cell containing the block coordinates. */
    public static Cell cellAt(double x, double z, int cellSize) {
        return new Cell(
                Math.floorDiv((int) Math.floor(x), cellSize),
                Math.floorDiv((int) Math.floor(z), cellSize));
    }

    /** size×size plot bounds at the center of the cell. */
    public static Bounds bounds(Cell cell, int cellSize, int size) {
        int offset = (cellSize - size) / 2;
        int minX = cell.x() * cellSize + offset;
        int minZ = cell.z() * cellSize + offset;
        return new Bounds(minX, minZ, minX + size - 1, minZ + size - 1);
    }

    /** Size determined by the best cross-season reputation tier (never shrinks once grown, D11). */
    public static int size(int current, int bestTier, int largeTier, int baseSize, int largeSize) {
        int wanted = bestTier >= largeTier ? largeSize : baseSize;
        return Math.max(current, wanted);
    }
}
