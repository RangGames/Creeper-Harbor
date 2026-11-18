package wiki.creeper.harbor.wildlife.sim;

/** 광선 ↔ 구 판정 ('17-3' 9.2 raycast: 작살 투척·보스 연출이 야생 개체 명중 확인). 순수 계산. */
public final class Raycast {

    private Raycast() {}

    /** 광선 o + t·d (d는 단위 벡터)가 구(c, r)에 처음 닿는 t. 시점이 구 안이면 0. 맞지 않거나 maxDistance보다 멀면 −1. */
    public static double sphere(
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double cx,
            double cy,
            double cz,
            double r,
            double maxDistance) {
        double lx = cx - ox;
        double ly = cy - oy;
        double lz = cz - oz;
        double c = lx * lx + ly * ly + lz * lz - r * r;
        if (c <= 0) {
            return 0;
        }
        double b = lx * dx + ly * dy + lz * dz;
        if (b < 0) {
            return -1;
        }
        double disc = b * b - c;
        if (disc < 0) {
            return -1;
        }
        double t = b - Math.sqrt(disc);
        return t <= maxDistance ? t : -1;
    }
}
