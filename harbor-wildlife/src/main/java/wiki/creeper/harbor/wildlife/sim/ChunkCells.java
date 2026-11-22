package wiki.creeper.harbor.wildlife.sim;

/**
 * Medium bit arrays for one chunk ('17-3' 9.4). 4,096 water/lava/air bits per section (16×16×16);
 * sections with no medium at all are left empty. Immutable once built, so the simulation thread
 * reads it without locking. When blocks change, only that section is replaced with a newly built
 * object ({@link #withSection}).
 */
public final class ChunkCells {

    /**
     * Medium bits of one cell (MediumField.WATER, LAVA, AIR; 0 if none). Chunk-local coordinates
     * (x, z 0~15, y is world height).
     */
    @FunctionalInterface
    public interface CellSource {
        int cell(int localX, int y, int localZ);
    }

    private static final int WORDS = 4096 / 64;

    private final int minSection;
    private final long[][] water;
    private final long[][] lava;
    private final long[][] air;

    private ChunkCells(int minSection, long[][] water, long[][] lava, long[][] air) {
        this.minSection = minSection;
        this.water = water;
        this.lava = lava;
        this.air = air;
    }

    /**
     * Builds it.
     *
     * @param minY bottom of the habitat height range (inclusive)
     * @param maxY top (inclusive)
     * @param mask medium bits to store (only media used by this region's species; air only when
     *     there are flying species)
     */
    public static ChunkCells build(int minY, int maxY, int mask, CellSource source) {
        int lo = Math.floorDiv(minY, 16);
        int hi = Math.floorDiv(maxY, 16);
        int n = Math.max(0, hi - lo + 1);
        long[][] w = new long[n][];
        long[][] l = new long[n][];
        long[][] a = new long[n][];
        for (int s = 0; s < n; s++) {
            fillSection(lo + s, minY, maxY, mask, source, w, l, a, s);
        }
        return new ChunkCells(lo, w, l, a);
    }

    private static void fillSection(
            int section,
            int minY,
            int maxY,
            int mask,
            CellSource source,
            long[][] w,
            long[][] l,
            long[][] a,
            int s) {
        long[] ws = null;
        long[] ls = null;
        long[] as = null;
        int baseY = section * 16;
        for (int dy = 0; dy < 16; dy++) {
            int y = baseY + dy;
            if (y < minY || y > maxY) {
                continue;
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int c = source.cell(x, y, z) & mask;
                    if (c == 0) {
                        continue;
                    }
                    int i = (dy << 8) | (z << 4) | x;
                    long b = 1L << (i & 63);
                    if ((c & MediumField.WATER) != 0) {
                        if (ws == null) {
                            ws = new long[WORDS];
                        }
                        ws[i >>> 6] |= b;
                    }
                    if ((c & MediumField.LAVA) != 0) {
                        if (ls == null) {
                            ls = new long[WORDS];
                        }
                        ls[i >>> 6] |= b;
                    }
                    if ((c & MediumField.AIR) != 0) {
                        if (as == null) {
                            as = new long[WORDS];
                        }
                        as[i >>> 6] |= b;
                    }
                }
            }
        }
        w[s] = ws;
        l[s] = ls;
        a[s] = as;
    }

    /**
     * New object with only one section rebuilt (block change). Unchanged if the section is out of
     * range.
     */
    public ChunkCells withSection(int section, int minY, int maxY, int mask, CellSource source) {
        int s = section - minSection;
        if (s < 0 || s >= water.length) {
            return this;
        }
        long[][] w = water.clone();
        long[][] l = lava.clone();
        long[][] a = air.clone();
        fillSection(section, minY, maxY, mask, source, w, l, a, s);
        return new ChunkCells(minSection, w, l, a);
    }

    /** Medium bits at chunk-local coordinates (x, z 0~15). */
    public int cell(int localX, int y, int localZ) {
        int s = Math.floorDiv(y, 16) - minSection;
        if (s < 0 || s >= water.length) {
            return 0;
        }
        int i = ((y & 15) << 8) | (localZ << 4) | localX;
        int word = i >>> 6;
        long b = 1L << (i & 63);
        int result = 0;
        long[] ws = water[s];
        if (ws != null && (ws[word] & b) != 0) {
            result |= MediumField.WATER;
        }
        long[] ls = lava[s];
        if (ls != null && (ls[word] & b) != 0) {
            result |= MediumField.LAVA;
        }
        long[] as = air[s];
        if (as != null && (as[word] & b) != 0) {
            result |= MediumField.AIR;
        }
        return result;
    }

    /** Bytes of stored section bit arrays (/harbor wildlife stats). */
    public int bytes() {
        int n = 0;
        for (int s = 0; s < water.length; s++) {
            n +=
                    (water[s] != null ? WORDS * 8 : 0)
                            + (lava[s] != null ? WORDS * 8 : 0)
                            + (air[s] != null ? WORDS * 8 : 0);
        }
        return n;
    }
}
