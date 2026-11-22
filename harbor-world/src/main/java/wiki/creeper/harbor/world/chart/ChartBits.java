package wiki.creeper.harbor.world.chart;

import java.util.Arrays;

/**
 * One player's regional sea chart bitmap (sea_chart.bits). A chunk bit array over the region's
 * bounding box, so even a 1,400×1,200 region is about 830 bytes (docs 20 8.4). Immutable: painting
 * a cell returns a new object (PlayerComponent value rule). claimed holds the bits of reward steps
 * already received (1 = step 1 …). No server API.
 */
public final class ChartBits {

    private final byte[] bits;
    private final int visited;
    private final int claimed;

    private ChartBits(byte[] bits, int visited, int claimed) {
        this.bits = bits;
        this.visited = visited;
        this.claimed = claimed;
    }

    public static ChartBits empty(ChartMask mask) {
        return new ChartBits(new byte[(mask.size() + 7) / 8], 0, 0);
    }

    /**
     * Value read from the DB. If the mask size changed (region bounds edited), resizes and recounts
     * the visited cells.
     */
    public static ChartBits of(ChartMask mask, byte[] raw, int claimed) {
        byte[] bits = Arrays.copyOf(raw, (mask.size() + 7) / 8);
        int count = 0;
        for (int cell = 0; cell < mask.size(); cell++) {
            if (get(bits, cell) && mask.navigable(cell)) {
                count++;
            }
        }
        return new ChartBits(bits, count, claimed);
    }

    public boolean has(int cell) {
        return cell >= 0 && get(bits, cell);
    }

    /** Paints a cell. Unchanged if already painted or out of range. */
    public ChartBits mark(ChartMask mask, int cell) {
        if (cell < 0 || cell >= bits.length * 8 || get(bits, cell)) {
            return this;
        }
        byte[] next = bits.clone();
        next[cell >> 3] |= (byte) (1 << (cell & 7));
        return new ChartBits(next, visited + (mask.navigable(cell) ? 1 : 0), claimed);
    }

    /**
     * Admin command (/harbor world chart set): a bitmap with the first ratio of navigable cells
     * painted.
     */
    public static ChartBits filled(ChartMask mask, double ratio, int claimed) {
        byte[] bits = new byte[(mask.size() + 7) / 8];
        int target = (int) Math.round(mask.total() * Math.max(0, Math.min(1, ratio)));
        int count = 0;
        for (int cell = 0; cell < mask.size() && count < target; cell++) {
            if (mask.navigable(cell)) {
                bits[cell >> 3] |= (byte) (1 << (cell & 7));
                count++;
            }
        }
        return new ChartBits(bits, count, claimed);
    }

    public ChartBits withClaimed(int newClaimed) {
        return new ChartBits(bits, visited, newClaimed);
    }

    public int visited() {
        return visited;
    }

    public int claimed() {
        return claimed;
    }

    public int claimedSteps() {
        return Integer.bitCount(claimed);
    }

    public boolean isClaimed(int step) {
        return (claimed & (1 << (step - 1))) != 0;
    }

    public double ratio(ChartMask mask) {
        return mask.total() <= 0 ? 0 : Math.min(1.0, (double) visited / mask.total());
    }

    /** Copy for saving. */
    public byte[] toBytes() {
        return bits.clone();
    }

    private static boolean get(byte[] bits, int cell) {
        int i = cell >> 3;
        return i < bits.length && (bits[i] & (1 << (cell & 7))) != 0;
    }
}
