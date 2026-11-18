package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.boss.content.BossDef;

/** 판정 기하 (서버 API 없음): 작살 궤적 선분 ↔ 판정 상자(AABB), 패턴 예고 범위 안에 있는지. */
public final class Geometry {

    private Geometry() {}

    /** 축 정렬 상자. */
    public record Aabb(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        /** Interaction 엔티티 상자: 발밑 중심 (x, y, z), 가로·세로 width, 높이 height. */
        public static Aabb interaction(double x, double y, double z, double width, double height) {
            double h = width / 2;
            return new Aabb(x - h, y, z - h, x + h, y + height, z + h);
        }

        public Aabb grow(double d) {
            return new Aabb(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /**
         * 선분 A→B가 상자와 만나는지 (slab 방식). 투사체는 Interaction과 충돌하지 않으므로 매 틱 직전 위치→현재 위치 선분으로 직접 계산한다
         * (docs 20 10.4 작살 궤적).
         *
         * @return 만나면 A에서의 비율 t (0~1), 아니면 −1
         */
        public double intersect(double ax, double ay, double az, double bx, double by, double bz) {
            double t0 = 0;
            double t1 = 1;
            double[] origin = {ax, ay, az};
            double[] dir = {bx - ax, by - ay, bz - az};
            double[] min = {minX, minY, minZ};
            double[] max = {maxX, maxY, maxZ};
            for (int i = 0; i < 3; i++) {
                if (Math.abs(dir[i]) < 1e-12) {
                    if (origin[i] < min[i] || origin[i] > max[i]) {
                        return -1;
                    }
                    continue;
                }
                double inv = 1.0 / dir[i];
                double near = (min[i] - origin[i]) * inv;
                double far = (max[i] - origin[i]) * inv;
                if (near > far) {
                    double tmp = near;
                    near = far;
                    far = tmp;
                }
                t0 = Math.max(t0, near);
                t1 = Math.min(t1, far);
                if (t0 > t1) {
                    return -1;
                }
            }
            return t0;
        }
    }

    /**
     * 바닥 예고 범위 (수평면). 방향 (dirX, dirZ)는 단위 벡터 (line·cone이 뻗는 쪽).
     *
     * @param size circle·cone 반경, line 폭, ring 바깥 반경
     * @param length line 길이
     * @param halfAngle cone 반각 (라디안)
     * @param inner ring 안쪽 반경
     */
    public record Area(
            BossDef.Shape shape,
            double ox,
            double oz,
            double dirX,
            double dirZ,
            double size,
            double length,
            double halfAngle,
            double inner) {

        public static Area of(BossDef.Telegraph t, double ox, double oz, double dirX, double dirZ) {
            double len = Math.hypot(dirX, dirZ);
            double dx = len < 1e-9 ? 0 : dirX / len;
            double dz = len < 1e-9 ? 1 : dirZ / len;
            return new Area(
                    t.shape(),
                    ox,
                    oz,
                    dx,
                    dz,
                    t.size(),
                    t.length(),
                    Math.toRadians(t.angle() / 2),
                    t.inner());
        }

        public boolean contains(double x, double z) {
            double rx = x - ox;
            double rz = z - oz;
            double dist = Math.hypot(rx, rz);
            return switch (shape) {
                case CIRCLE -> dist <= size;
                case RING -> dist <= size && dist >= inner;
                case LINE -> {
                    double along = rx * dirX + rz * dirZ;
                    double side = Math.abs(-rx * dirZ + rz * dirX);
                    yield along >= 0 && along <= length && side <= size / 2;
                }
                case CONE -> {
                    if (dist > size) {
                        yield false;
                    }
                    if (dist < 1e-9) {
                        yield true;
                    }
                    double cos = (rx * dirX + rz * dirZ) / dist;
                    yield cos >= Math.cos(halfAngle) - 1e-9;
                }
            };
        }

        /**
         * 가장자리 파티클 점 (x, z). scale은 1 → 0으로 줄어드는 예고 진행 (중심 쪽으로 오므라든다).
         *
         * @param points 대략적인 점 개수
         */
        public List<double[]> outline(int points, double scale) {
            List<double[]> out = new ArrayList<>();
            double k = Math.clamp(scale, 0.05, 1.0);
            int n = Math.max(8, points);
            switch (shape) {
                case CIRCLE, RING -> {
                    for (int i = 0; i < n; i++) {
                        double a = 2 * Math.PI * i / n;
                        out.add(
                                new double[] {
                                    ox + Math.cos(a) * size * k, oz + Math.sin(a) * size * k
                                });
                    }
                }
                case LINE -> {
                    double half = size / 2 * k;
                    double len = length * k;
                    double px = -dirZ;
                    double pz = dirX;
                    int side = Math.max(2, n / 2);
                    for (int i = 0; i <= side; i++) {
                        double along = len * i / side;
                        double cx = ox + dirX * along;
                        double cz = oz + dirZ * along;
                        out.add(new double[] {cx + px * half, cz + pz * half});
                        out.add(new double[] {cx - px * half, cz - pz * half});
                    }
                }
                case CONE -> {
                    double r = size * k;
                    double base = Math.atan2(dirZ, dirX);
                    for (int i = 0; i <= n; i++) {
                        double a = base - halfAngle + 2 * halfAngle * i / n;
                        out.add(new double[] {ox + Math.cos(a) * r, oz + Math.sin(a) * r});
                    }
                    int edge = Math.max(2, n / 4);
                    for (int i = 1; i < edge; i++) {
                        double d = r * i / edge;
                        out.add(
                                new double[] {
                                    ox + Math.cos(base - halfAngle) * d,
                                    oz + Math.sin(base - halfAngle) * d
                                });
                        out.add(
                                new double[] {
                                    ox + Math.cos(base + halfAngle) * d,
                                    oz + Math.sin(base + halfAngle) * d
                                });
                    }
                }
            }
            return out;
        }

        /** 바닥 판(BlockDisplay)을 깔 1×1 칸 중심들 (범위 안 칸). */
        public List<double[]> cells(double step) {
            List<double[]> out = new ArrayList<>();
            double reach = shape == BossDef.Shape.LINE ? Math.max(length, size) : size;
            for (double x = -reach; x <= reach; x += step) {
                for (double z = -reach; z <= reach; z += step) {
                    double cx = ox + x;
                    double cz = oz + z;
                    if (contains(cx, cz)) {
                        out.add(new double[] {cx, cz});
                    }
                }
            }
            return out;
        }
    }
}
