package wiki.creeper.harbor.wildlife.sim;

/** 위협 감지 규칙 ('17-3' 9.4 위협 감지 · 도망). 순수 계산. */
public final class ThreatRules {

    private ThreatRules() {}

    /** 실제 감지 반경. 웅크리면 ×sneak, 보트에 타고 있으면 ×boat, 같은 플레이어에게 최근 놀랐으면 ×habituate. */
    public static double radius(
            double base,
            boolean sneaking,
            boolean boat,
            boolean habituated,
            double sneakFactor,
            double boatFactor,
            double habituateFactor) {
        double r = base;
        if (sneaking) {
            r *= sneakFactor;
        }
        if (boat) {
            r *= boatFactor;
        }
        if (habituated) {
            r *= habituateFactor;
        }
        return r;
    }

    /**
     * 발동 여부: 반경 안에서 물고기 쪽으로 다가오는 속도가 기준 이상이거나, 반경의 innerRatio 안까지 들어오면.
     *
     * @param closing 물고기 쪽으로 다가오는 속도 (블록/초, 멀어지면 음수)
     */
    public static boolean triggered(
            double distance,
            double closing,
            double radius,
            double closingThreshold,
            double innerRatio) {
        if (radius <= 0 || distance > radius) {
            return false;
        }
        return closing >= closingThreshold || distance <= radius * innerRatio;
    }

    /** 다가오는 속도 = 플레이어 속도를 (물고기 − 플레이어) 방향에 투영한 값. */
    public static double closing(
            double px,
            double py,
            double pz,
            double vx,
            double vy,
            double vz,
            double fx,
            double fy,
            double fz) {
        double dx = fx - px;
        double dy = fy - py;
        double dz = fz - pz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-9) {
            return 0;
        }
        return (vx * dx + vy * dy + vz * dz) / d;
    }
}
