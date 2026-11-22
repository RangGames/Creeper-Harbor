package wiki.creeper.harbor.world.geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk → region index of one world (docs 20 8.4 'region detection'). Built on a virtual thread at
 * content load and immutable afterwards, so any thread may read it. A chunk fully inside one region
 * resolves with a single array lookup; only chunks on a boundary run the polygon test for candidate
 * regions.
 *
 * <p>When regions overlap, the earlier one in the list wins (regions.yml order).
 */
public final class RegionIndex {

    /** Cell with no region. */
    public static final int NONE = -1;

    private static final short EMPTY = 0;
    private static final short MIXED = -1;

    private final List<Polygon> polygons;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final short[] cells;
    private final Map<Long, int[]> mixed;

    private RegionIndex(
            List<Polygon> polygons,
            int originX,
            int originZ,
            int width,
            int depth,
            short[] cells,
            Map<Long, int[]> mixed) {
        this.polygons = polygons;
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.cells = cells;
        this.mixed = mixed;
    }

    /** Empty index (a world with no regions). */
    public static RegionIndex empty() {
        return new RegionIndex(List.of(), 0, 0, 0, 0, new short[0], Map.of());
    }

    /**
     * @param polygons region bounds (index = list order)
     */
    public static RegionIndex build(List<Polygon> polygons) {
        if (polygons.isEmpty()) {
            return empty();
        }
        if (polygons.size() > Short.MAX_VALUE - 1) {
            throw new IllegalArgumentException("지역이 너무 많습니다: " + polygons.size());
        }
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Polygon p : polygons) {
            minX = Math.min(minX, p.minX());
            minZ = Math.min(minZ, p.minZ());
            maxX = Math.max(maxX, p.maxX());
            maxZ = Math.max(maxZ, p.maxZ());
        }
        int cx0 = Math.floorDiv((int) Math.floor(minX), 16);
        int cz0 = Math.floorDiv((int) Math.floor(minZ), 16);
        int cx1 = Math.floorDiv((int) Math.floor(maxX), 16);
        int cz1 = Math.floorDiv((int) Math.floor(maxZ), 16);
        int width = cx1 - cx0 + 1;
        int depth = cz1 - cz0 + 1;
        short[] cells = new short[width * depth];
        Map<Long, int[]> mixed = new HashMap<>();
        List<Integer> candidates = new ArrayList<>();
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                int cx = cx0 + dx;
                int cz = cz0 + dz;
                candidates.clear();
                int full = NONE;
                for (int r = 0; r < polygons.size(); r++) {
                    Polygon.Cover cover = polygons.get(r).cover(cx * 16.0, cz * 16.0, 16.0);
                    if (cover == Polygon.Cover.OUTSIDE) {
                        continue;
                    }
                    candidates.add(r);
                    if (cover == Polygon.Cover.INSIDE) {
                        full = r;
                        // If an earlier region covers it fully, later regions need not be checked
                        break;
                    }
                }
                int cell = dz * width + dx;
                if (candidates.isEmpty()) {
                    cells[cell] = EMPTY;
                } else if (candidates.size() == 1 && full != NONE) {
                    cells[cell] = (short) (full + 1);
                } else {
                    cells[cell] = MIXED;
                    mixed.put(
                            key(cx, cz), candidates.stream().mapToInt(Integer::intValue).toArray());
                }
            }
        }
        return new RegionIndex(
                List.copyOf(polygons), cx0, cz0, width, depth, cells, Map.copyOf(mixed));
    }

    /** Region index at block coordinates. {@link #NONE} if none. */
    public int lookup(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        int dx = cx - originX;
        int dz = cz - originZ;
        if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
            return NONE;
        }
        short cell = cells[dz * width + dx];
        if (cell == EMPTY) {
            return NONE;
        }
        if (cell > 0) {
            return cell - 1;
        }
        int[] candidates = mixed.get(key(cx, cz));
        if (candidates == null) {
            return NONE;
        }
        for (int r : candidates) {
            if (polygons.get(r).contains(x, z)) {
                return r;
            }
        }
        return NONE;
    }

    /**
     * The region index if the chunk lies fully inside one region, otherwise {@link #NONE} (for the
     * chunk cache).
     */
    public int wholeChunk(int cx, int cz) {
        int dx = cx - originX;
        int dz = cz - originZ;
        if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
            return NONE;
        }
        short cell = cells[dz * width + dx];
        return cell > 0 ? cell - 1 : NONE;
    }

    /** Number of chunks straddling a boundary (/harbor world region info stats). */
    public int mixedChunks() {
        return mixed.size();
    }

    public int chunkCount() {
        return cells.length;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
