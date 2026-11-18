package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * 자연 스폰 자리 ('17-3' 9.4): 플레이어 기준 16~40블록 고리에서 무작위 점, 매질 깊이(물·용암 2칸 이상, 공중 여유 3칸) 확인, 다른 무리 중심과 8블록
 * 이상. 순수 계산.
 */
public final class SpawnPlanner {

    /**
     * 찾은 자리.
     *
     * @param depth 수면 아래 깊이 (공중이면 0)
     * @param floorY 그 기둥의 바닥 (매질이 끝나는 칸 위 y, 바닥 개체 자리)
     */
    public record Spot(Vec3 pos, Medium medium, double depth, int floorY) {}

    private SpawnPlanner() {}

    /** 고리 안 무작위 점 (넓이 기준 균일). */
    public static double[] ringPoint(
            double cx, double cz, double rMin, double rMax, RandomGenerator random) {
        double a = random.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(rMin * rMin + random.nextDouble() * (rMax * rMax - rMin * rMin));
        return new double[] {cx + Math.cos(a) * r, cz + Math.sin(a) * r};
    }

    /**
     * 한 기둥(x, z)에서 yTop부터 yBottom까지 내려가며 매질 구간을 찾고, 조건에 맞는 구간 안 무작위 높이를 고른다.
     *
     * @param minDepth 물·용암 구간의 최소 두께 (칸)
     * @param clearance 공중 구간의 최소 두께 (칸)
     */
    public static Optional<Spot> column(
            MediumField field,
            Medium medium,
            int x,
            int z,
            int yTop,
            int yBottom,
            int minDepth,
            int clearance,
            RandomGenerator random) {
        Medium cellMedium = medium.cell();
        int need = cellMedium == Medium.AIR ? clearance : minDepth;
        record Segment(int top, int bottom) {}
        List<Segment> segments = new ArrayList<>();
        int y = yTop;
        while (y >= yBottom) {
            if (!field.is(cellMedium, x, y, z)) {
                y--;
                continue;
            }
            int top = y;
            while (y >= yBottom && field.is(cellMedium, x, y, z)) {
                y--;
            }
            int bottom = y + 1;
            if (top - bottom + 1 >= need) {
                segments.add(new Segment(top, bottom));
            }
        }
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        Segment s = segments.get(random.nextInt(segments.size()));
        // 수면: 구간 꼭대기 위 칸이 매질이 아니면 그 칸의 바닥이 수면 (yTop에서 잘렸으면 위로 더 본다)
        int surface = s.top() + 1;
        while (surface <= yTop + 32 && field.is(cellMedium, x, surface, z)) {
            surface++;
        }
        if (medium == Medium.FLOOR) {
            return Optional.of(
                    new Spot(
                            new Vec3(x + 0.5, s.bottom() + 0.15, z + 0.5),
                            medium,
                            surface - s.bottom(),
                            s.bottom()));
        }
        double py;
        if (cellMedium == Medium.AIR) {
            py = s.bottom() + 1 + random.nextDouble() * Math.max(0, s.top() - s.bottom() - 1);
        } else {
            // 수면 바로 아래 칸은 피한다 (수면 아래 0.5블록 유지)
            int hi = Math.max(s.bottom(), s.top() - 1);
            py = s.bottom() + 0.5 + random.nextDouble() * Math.max(0, hi - s.bottom());
        }
        double depth = cellMedium == Medium.AIR ? 0 : surface - py;
        return Optional.of(new Spot(new Vec3(x + 0.5, py, z + 0.5), medium, depth, s.bottom()));
    }

    /** 다른 무리 중심과 gap 이상 떨어졌는지. */
    public static boolean farFrom(Vec3 p, List<Vec3> centers, double gap) {
        double g2 = gap * gap;
        for (Vec3 c : centers) {
            if (Steer.distanceSq(p, c) < g2) {
                return false;
            }
        }
        return true;
    }

    public static int schoolSize(int min, int max, RandomGenerator random) {
        int lo = Math.max(1, min);
        int hi = Math.max(lo, max);
        return lo + random.nextInt(hi - lo + 1);
    }
}
