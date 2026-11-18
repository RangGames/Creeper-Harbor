package wiki.creeper.harbor.world.geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 월드 하나의 청크 → 지역 색인 (docs 20 8.4 '지역 판정'). 콘텐츠 로드 때 가상 스레드에서 만들고, 만든 뒤에는 변경 불가라 어느 스레드에서 읽어도 된다.
 * 청크가 한 지역 안에 통째로 들어가면 배열 조회 한 번으로 끝나고, 경계에 걸친 청크만 후보 지역의 다각형 판정을 한다.
 *
 * <p>지역이 겹치면 목록 앞의 지역이 이긴다 (regions.yml 순서).
 */
public final class RegionIndex {

    /** 지역이 없는 칸. */
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

    /** 빈 색인 (지역이 없는 월드). */
    public static RegionIndex empty() {
        return new RegionIndex(List.of(), 0, 0, 0, 0, new short[0], Map.of());
    }

    /**
     * @param polygons 지역 경계 (번호 = 목록 순서)
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
                        // 앞 지역이 통째로 덮으면 뒤 지역은 볼 필요가 없다
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

    /** 블록 좌표의 지역 번호. 없으면 {@link #NONE}. */
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

    /** 청크가 한 지역에 통째로 들어가면 그 번호, 아니면 {@link #NONE} (청크 캐시용). */
    public int wholeChunk(int cx, int cz) {
        int dx = cx - originX;
        int dz = cz - originZ;
        if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
            return NONE;
        }
        short cell = cells[dz * width + dx];
        return cell > 0 ? cell - 1 : NONE;
    }

    /** 경계에 걸친 청크 수 (/harbor world region info 통계). */
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
