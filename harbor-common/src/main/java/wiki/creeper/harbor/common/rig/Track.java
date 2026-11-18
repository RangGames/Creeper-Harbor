package wiki.creeper.harbor.common.rig;

import java.util.List;

/**
 * 채널 하나(이동·회전·크기)의 키프레임 목록. 키는 시각 순서로 정렬되어 있어야 한다 (RigDef 파서가 검사).
 *
 * <p>회전 채널의 값은 오일러 각(도)이고 성분별로 보간한 뒤 사원수로 바꾼다. Blockbench와 같은 방식이라 0→360° 한 바퀴 회전도 표현된다.
 */
public record Track(List<Keyframe> keys) {

    public static final Track EMPTY = new Track(List.of());

    /** 키 하나. t는 애니메이션 시작부터의 틱. */
    public record Keyframe(double t, Vec3 value, Easing easing) {}

    public Track {
        keys = List.copyOf(keys);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** 시각 t의 값. 키가 없으면 fallback, 첫 키 앞이면 첫 값, 마지막 키 뒤면 마지막 값. */
    public Vec3 sample(double t, Vec3 fallback) {
        int n = keys.size();
        if (n == 0) {
            return fallback;
        }
        if (n == 1 || t <= keys.getFirst().t()) {
            return keys.getFirst().value();
        }
        if (t >= keys.getLast().t()) {
            return keys.getLast().value();
        }
        int i = segment(t);
        Keyframe a = keys.get(i);
        Keyframe b = keys.get(i + 1);
        double span = b.t() - a.t();
        double f = span <= 0 ? 1 : (t - a.t()) / span;
        if (a.easing() == Easing.CATMULLROM) {
            Vec3 p0 = keys.get(Math.max(0, i - 1)).value();
            Vec3 p3 = keys.get(Math.min(n - 1, i + 2)).value();
            return Vec3.catmullRom(p0, a.value(), b.value(), p3, f);
        }
        return Vec3.lerp(a.value(), b.value(), a.easing().apply(f));
    }

    /** t가 들어 있는 구간의 시작 키 번호 (keys[i].t ≤ t < keys[i+1].t). */
    private int segment(double t) {
        int lo = 0;
        int hi = keys.size() - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (keys.get(mid).t() <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
