package wiki.creeper.harbor.wildlife.sim;

/**
 * 청크 하나의 매질 비트 배열 ('17-3' 9.4). 섹션(16×16×16)마다 물·용암·공기 비트 4,096개씩, 매질이 하나도 없는 섹션은 비워 둔다. 만든 뒤에는 바뀌지
 * 않으므로 계산 스레드가 잠금 없이 읽는다. 블록이 바뀌면 그 섹션만 새로 만든 객체로 교체한다 ({@link #withSection}).
 */
public final class ChunkCells {

    /** 칸 하나의 매질 비트 (MediumField.WATER · LAVA · AIR, 없으면 0). 청크 안 좌표 (x, z 0~15, y는 월드 높이). */
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
     * 만든다.
     *
     * @param minY 서식지 높이 범위 아래 (포함)
     * @param maxY 위 (포함)
     * @param mask 담을 매질 비트 (이 지역 어종이 쓰는 매질만, 공기는 비행 어종이 있을 때만)
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

    /** 섹션 하나만 다시 만든 새 객체 (블록 변경). 범위 밖 섹션이면 그대로. */
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

    /** 청크 안 좌표 (x, z 0~15)의 매질 비트. */
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

    /** 담긴 섹션 비트 배열 바이트 수 (/harbor wildlife stats). */
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
