package wiki.creeper.harbor.world.chart;

import java.util.BitSet;
import wiki.creeper.harbor.world.geo.Polygon;

/**
 * 해도 항해 가능 마스크 (docs 20 8.4 '해도'). 지역 경계 상자를 청크 칸으로 나누고, 칸 가운데가 지역 안이면 항해 가능으로 본다. 맵이 확정되면
 * harbor-tools가 만드는 charts/<지역>.mask(육지·막힌 곳 제외)로 바꾼다 (추가 제안, 아직 없음). 변경 불가, 서버 API 없음.
 */
public final class ChartMask {

    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final BitSet cells;
    private final int total;

    private ChartMask(int originX, int originZ, int width, int depth, BitSet cells) {
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.cells = cells;
        this.total = cells.cardinality();
    }

    public static ChartMask of(Polygon polygon) {
        if (polygon.size() < 3) {
            return new ChartMask(0, 0, 0, 0, new BitSet());
        }
        int cx0 = Math.floorDiv((int) Math.floor(polygon.minX()), 16);
        int cz0 = Math.floorDiv((int) Math.floor(polygon.minZ()), 16);
        int cx1 = Math.floorDiv((int) Math.floor(polygon.maxX()), 16);
        int cz1 = Math.floorDiv((int) Math.floor(polygon.maxZ()), 16);
        int width = cx1 - cx0 + 1;
        int depth = cz1 - cz0 + 1;
        BitSet cells = new BitSet(width * depth);
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                double x = (cx0 + dx) * 16 + 8;
                double z = (cz0 + dz) * 16 + 8;
                if (polygon.contains(x, z)) {
                    cells.set(dz * width + dx);
                }
            }
        }
        return new ChartMask(cx0, cz0, width, depth, cells);
    }

    /** 청크의 칸 번호. 경계 상자 밖이면 −1. */
    public int cell(int cx, int cz) {
        int dx = cx - originX;
        int dz = cz - originZ;
        if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
            return -1;
        }
        return dz * width + dx;
    }

    public boolean navigable(int cell) {
        return cell >= 0 && cells.get(cell);
    }

    /** 칸 수 (비트 배열 크기). */
    public int size() {
        return width * depth;
    }

    /** 항해 가능 칸 수. */
    public int total() {
        return total;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }
}
