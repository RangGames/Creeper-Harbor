package wiki.creeper.harbor.wildlife.sim;

import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * 매질 조회 ('17-3' 9.4 매질 격자). 칸마다 물·용암·공기 비트를 돌려준다. 적재되지 않았거나 격자가 없는 칸은 0 (막힌 칸으로 본다). 계산 스레드가 잠금 없이
 * 읽으므로 구현은 변경 불가 자료만 읽어야 한다.
 */
@FunctionalInterface
public interface MediumField {

    int WATER = 1;
    int LAVA = 2;
    int AIR = 4;

    /** 아무것도 없는 필드 (모든 칸이 막힘). */
    MediumField EMPTY = (x, y, z) -> 0;

    int cell(int x, int y, int z);

    static int bit(Medium medium) {
        return switch (medium.cell()) {
            case LAVA -> LAVA;
            case AIR -> AIR;
            default -> WATER;
        };
    }

    default boolean is(Medium medium, int x, int y, int z) {
        return (cell(x, y, z) & bit(medium)) != 0;
    }

    default boolean is(Medium medium, double x, double y, double z) {
        return is(medium, floor(x), floor(y), floor(z));
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }
}
