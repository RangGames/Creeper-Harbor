package wiki.creeper.harbor.common.rig;

import wiki.creeper.harbor.common.model.Keyed;

/** 키프레임 사이 보간 방식. 키에 적힌 방식이 그 키에서 다음 키까지의 구간에 쓰인다. */
public enum Easing implements Keyed {
    LINEAR,
    /** 다음 키까지 값을 유지하다가 바로 바뀐다. */
    STEP,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    /** 앞뒤 키까지 보고 부드럽게 잇는다 (이동·크기·오일러 각 모두). */
    CATMULLROM;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** 구간 진행률 f(0~1)를 곡선에 맞춰 바꾼다. CATMULLROM은 {@link Track}에서 따로 계산하므로 그대로 돌려준다. */
    public double apply(double f) {
        return switch (this) {
            case LINEAR, CATMULLROM -> f;
            case STEP -> 0;
            case EASE_IN -> f * f * f;
            case EASE_OUT -> 1 - Math.pow(1 - f, 3);
            case EASE_IN_OUT -> f < 0.5 ? 4 * f * f * f : 1 - Math.pow(-2 * f + 2, 3) / 2;
        };
    }
}
