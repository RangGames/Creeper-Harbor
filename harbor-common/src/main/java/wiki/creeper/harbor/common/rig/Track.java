package wiki.creeper.harbor.common.rig;

import java.util.List;

/**
 * Keyframe list of one channel (translation, rotation or scale). Keys must be sorted by time
 * (checked by the RigDef parser).
 *
 * <p>Rotation channel values are Euler angles (degrees), interpolated per component and then
 * converted to a quaternion. Same as Blockbench, so a full 0→360° turn can be expressed.
 */
public record Track(List<Keyframe> keys) {

    public static final Track EMPTY = new Track(List.of());

    /** One key. t is ticks since the animation start. */
    public record Keyframe(double t, Vec3 value, Easing easing) {}

    public Track {
        keys = List.copyOf(keys);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /**
     * Value at time t. fallback if there are no keys, the first value before the first key, the
     * last value after the last key.
     */
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

    /** Start key index of the interval containing t (keys[i].t ≤ t < keys[i+1].t). */
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
