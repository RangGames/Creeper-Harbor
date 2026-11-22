package wiki.creeper.harbor.common.rig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rig precomputed for playback. Holds compressed per-part keyframes and hitbox positions per
 * animation. Immutable, so it can be read from any thread.
 *
 * @param parts bones with parts (one Display entity each)
 * @param hitboxes bones with hitboxes (one Interaction entity each)
 * @param rest rest pose (length 0, one key)
 */
public record BakedRig(
        RigDef def,
        List<PartRef> parts,
        List<HitboxRef> hitboxes,
        Map<String, BakedAnimation> animations,
        BakedAnimation rest,
        int ticksPerFrame) {

    /** Part index → bone. */
    public record PartRef(int bone, String name, RigDef.Part part) {}

    /** Hitbox index → bone. */
    public record HitboxRef(int bone, String name, RigDef.Hitbox hitbox) {}

    public Optional<BakedAnimation> animation(String name) {
        return Optional.ofNullable(animations.get(name));
    }

    /**
     * One animation (baked result). Length in ticks; holds key lists in part index order and
     * [frame][hitbox] box centers (relative to the rig origin).
     */
    public static final class BakedAnimation {

        private final String name;
        private final int length;
        private final RigDef.LoopMode mode;
        private final int ticksPerFrame;
        private final List<KeyTrack> parts;
        private final Vec3[][] hitboxFrames;

        public BakedAnimation(
                String name,
                int length,
                RigDef.LoopMode mode,
                int ticksPerFrame,
                List<KeyTrack> parts,
                Vec3[][] hitboxFrames) {
            this.name = name;
            this.length = length;
            this.mode = mode;
            this.ticksPerFrame = ticksPerFrame;
            this.parts = List.copyOf(parts);
            this.hitboxFrames = hitboxFrames.clone();
        }

        public String name() {
            return name;
        }

        public int length() {
            return length;
        }

        public RigDef.LoopMode mode() {
            return mode;
        }

        public int ticksPerFrame() {
            return ticksPerFrame;
        }

        public List<KeyTrack> parts() {
            return parts;
        }

        public KeyTrack part(int index) {
            return parts.get(index);
        }

        public int frameCount() {
            return hitboxFrames.length;
        }

        /** Hitbox centers at time t (linear interpolation between frames). */
        public Vec3 hitbox(int index, double t) {
            int frames = hitboxFrames.length;
            if (frames == 1 || length <= 0) {
                return hitboxFrames[0][index];
            }
            double f = Math.max(0, Math.min(t, length)) / ticksPerFrame;
            int a = Math.min(frames - 1, (int) Math.floor(f));
            int b = Math.min(frames - 1, a + 1);
            return Vec3.lerp(hitboxFrames[a][index], hitboxFrames[b][index], Math.min(1, f - a));
        }

        /** Total key count (to check compression). */
        public int keyCount() {
            int n = 0;
            for (KeyTrack track : parts) {
                n += track.size();
            }
            return n;
        }
    }

    /**
     * Compressed keys of one part. Times are ascending; the first key is 0 and the last is the
     * animation length. The client interpolates linearly/spherically between keys.
     */
    public static final class KeyTrack {

        private final int[] ticks;
        private final Xform[] values;

        public KeyTrack(int[] ticks, Xform[] values) {
            if (ticks.length == 0 || ticks.length != values.length) {
                throw new IllegalArgumentException("키 시각과 값의 수가 맞지 않습니다");
            }
            this.ticks = ticks.clone();
            this.values = values.clone();
        }

        public int size() {
            return ticks.length;
        }

        public int tick(int i) {
            return ticks[i];
        }

        public Xform value(int i) {
            return values[i];
        }

        /**
         * Start key index of the interval containing t (tick(i) ≤ t < tick(i+1)). The last index if
         * past the last key.
         */
        public int segment(double t) {
            int n = ticks.length;
            if (n == 1 || t < ticks[0]) {
                return 0;
            }
            if (t >= ticks[n - 1]) {
                return n - 1;
            }
            int lo = 0;
            int hi = n - 2;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (ticks[mid] <= t) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        /** Value at time t (interpolated between keys, same as the client). */
        public Xform sample(double t) {
            int i = segment(t);
            if (i >= ticks.length - 1) {
                return values[ticks.length - 1];
            }
            double span = ticks[i + 1] - ticks[i];
            double f = span <= 0 ? 1 : (t - ticks[i]) / span;
            return Xform.interpolate(values[i], values[i + 1], Math.max(0, Math.min(1, f)));
        }
    }
}
