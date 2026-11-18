package wiki.creeper.harbor.world.chart;

import java.util.Arrays;

/**
 * 플레이어 한 명의 지역 해도 비트맵 (sea_chart.bits). 지역 경계 상자 기준 청크 비트 배열이라 1,400×1,200 지역도 약 830바이트다 (docs 20
 * 8.4). 변경 불가: 칸을 칠하면 새 객체를 돌려준다 (PlayerComponent 값 규칙). claimed는 받은 보상 단계 비트 (1 = 1단계 …). 서버 API
 * 없음.
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

    /** DB에서 읽은 값. 마스크 크기가 바뀌었으면(지역 경계 수정) 크기를 맞추고 방문 수를 다시 센다. */
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

    /** 칸을 칠한다. 이미 칠했거나 범위 밖이면 그대로. */
    public ChartBits mark(ChartMask mask, int cell) {
        if (cell < 0 || cell >= bits.length * 8 || get(bits, cell)) {
            return this;
        }
        byte[] next = bits.clone();
        next[cell >> 3] |= (byte) (1 << (cell & 7));
        return new ChartBits(next, visited + (mask.navigable(cell) ? 1 : 0), claimed);
    }

    /** 관리 명령 (/harbor world chart set): 항해 가능 칸 앞에서부터 ratio만큼 칠한 비트맵. */
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

    /** 저장용 복사본. */
    public byte[] toBytes() {
        return bits.clone();
    }

    private static boolean get(byte[] bits, int cell) {
        int i = cell >> 3;
        return i < bits.length && (bits[i] & (1 << (cell & 7))) != 0;
    }
}
