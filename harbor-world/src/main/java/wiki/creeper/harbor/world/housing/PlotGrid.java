package wiki.creeper.harbor.world.housing;

/**
 * 하우징 플롯 칸 배치 (docs 20 8.4 '플롯 배치'). harbor_homes 월드에 cell×cell 칸(32 최대 크기 + 길 8, 제안값)을 원점에서 나선형
 * 순서로 배정한다. 플롯 번호 n ↔ 칸 (gx, gz)는 서로 바꿀 수 있다. 플롯은 칸 가운데에 size×size로 놓이고, 크기가 커져도 가운데 기준으로 넓어진다. 서버
 * API 없음 (단위 테스트).
 */
public final class PlotGrid {

    private PlotGrid() {}

    /** 칸 좌표. */
    public record Cell(int x, int z) {}

    /** 플롯 경계 (블록, 양 끝 포함). */
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

    /** n번째 칸 (0 = 원점, 그다음 오른쪽부터 시계 반대 방향 나선). */
    public static Cell cell(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("플롯 번호는 0 이상입니다: " + n);
        }
        if (n == 0) {
            return new Cell(0, 0);
        }
        // 고리 k: 칸 수 8k, 시작 번호 (2k-1)^2
        long k = (long) Math.ceil((Math.sqrt((double) (n + 1)) - 1) / 2);
        long side = 2 * k;
        long start = (2 * k - 1) * (2 * k - 1);
        long t = n - start;
        long x;
        long z;
        if (t < side) { // 오른쪽 변, 아래에서 위로
            x = k;
            z = -k + 1 + t;
        } else if (t < 2 * side) { // 위쪽 변, 오른쪽에서 왼쪽
            x = k - 1 - (t - side);
            z = k;
        } else if (t < 3 * side) { // 왼쪽 변, 위에서 아래
            x = -k;
            z = k - 1 - (t - 2 * side);
        } else { // 아래쪽 변, 왼쪽에서 오른쪽
            x = -k + 1 + (t - 3 * side);
            z = -k;
        }
        return new Cell((int) x, (int) z);
    }

    /** 칸 → 플롯 번호 ({@link #cell}의 역함수). */
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

    /** 블록 좌표가 속한 칸. */
    public static Cell cellAt(double x, double z, int cellSize) {
        return new Cell(
                Math.floorDiv((int) Math.floor(x), cellSize),
                Math.floorDiv((int) Math.floor(z), cellSize));
    }

    /** 칸 가운데의 size×size 플롯 경계. */
    public static Bounds bounds(Cell cell, int cellSize, int size) {
        int offset = (cellSize - size) / 2;
        int minX = cell.x() * cellSize + offset;
        int minZ = cell.z() * cellSize + offset;
        return new Bounds(minX, minZ, minX + size - 1, minZ + size - 1);
    }

    /** 시즌 간 최고 평판 등급으로 정한 크기 (한 번 늘어나면 줄지 않는다, D11). */
    public static int size(int current, int bestTier, int largeTier, int baseSize, int largeSize) {
        int wanted = bestTier >= largeTier ? largeSize : baseSize;
        return Math.max(current, wanted);
    }
}
