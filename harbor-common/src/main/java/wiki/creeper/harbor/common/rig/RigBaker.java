package wiki.creeper.harbor.common.rig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Bakes rig definitions for playback ('17-3' 9.4 animation): samples animations at a fixed fps
 * (prototype 10fps) and compresses keyframes per part. Compression starts from the previous key and
 * skips frames that linear interpolation can replace within tolerance (prototype: 0.005 blocks
 * translation, 1.2° rotation); key spacing never exceeds maxGap frames (prototype 8). During
 * playback, interpolation commands toward the next key value are sent only on key frames.
 */
public final class RigBaker {

    /**
     * Bake settings (balance.yml rig section).
     *
     * @param fps samples per second. Must divide 20 (1, 2, 4, 5, 10, 20)
     * @param maxGap max frames between keys
     */
    public record Settings(int fps, Xform.Tolerance tolerance, int maxGap) {

        public Settings {
            if (fps <= 0 || 20 % fps != 0) {
                throw new IllegalArgumentException("fps는 20의 약수여야 합니다: " + fps);
            }
            if (maxGap < 1) {
                throw new IllegalArgumentException("maxGap은 1 이상이어야 합니다: " + maxGap);
            }
        }

        public int ticksPerFrame() {
            return 20 / fps;
        }
    }

    private RigBaker() {}

    public static BakedRig bake(RigDef rig, Settings settings) {
        List<BakedRig.PartRef> parts = new ArrayList<>();
        List<BakedRig.HitboxRef> hitboxes = new ArrayList<>();
        for (int i = 0; i < rig.bones().size(); i++) {
            RigDef.Bone bone = rig.bones().get(i);
            RigDef.Part part = bone.part();
            if (part != null) {
                parts.add(new BakedRig.PartRef(i, bone.name(), part));
            }
            RigDef.Hitbox hitbox = bone.hitbox();
            if (hitbox != null) {
                hitboxes.add(new BakedRig.HitboxRef(i, bone.name(), hitbox));
            }
        }
        int tpf = settings.ticksPerFrame();
        Map<String, BakedRig.BakedAnimation> animations = new LinkedHashMap<>();
        for (RigDef.Animation animation : rig.animations().values()) {
            animations.put(
                    animation.name(),
                    bakeAnimation(
                            rig,
                            animation,
                            animation.name(),
                            animation.length(),
                            animation.mode(),
                            parts,
                            hitboxes,
                            settings));
        }
        BakedRig.BakedAnimation rest =
                bakeAnimation(rig, null, "", 0, RigDef.LoopMode.HOLD, parts, hitboxes, settings);
        return new BakedRig(
                rig, List.copyOf(parts), List.copyOf(hitboxes), Map.copyOf(animations), rest, tpf);
    }

    /** Frame times: 0, tpf, 2·tpf, …; the last is always length. */
    static int[] frameTicks(int length, int tpf) {
        if (length <= 0) {
            return new int[] {0};
        }
        int frames = (length + tpf - 1) / tpf;
        int[] ticks = new int[frames + 1];
        for (int f = 0; f < frames; f++) {
            ticks[f] = f * tpf;
        }
        ticks[frames] = length;
        return ticks;
    }

    private static BakedRig.BakedAnimation bakeAnimation(
            RigDef rig,
            RigDef.@Nullable Animation animation,
            String name,
            int length,
            RigDef.LoopMode mode,
            List<BakedRig.PartRef> parts,
            List<BakedRig.HitboxRef> hitboxes,
            Settings settings) {
        int[] ticks = frameTicks(length, settings.ticksPerFrame());
        int frames = ticks.length;
        Xform[][] partFrames = new Xform[parts.size()][frames];
        Vec3[][] hitboxFrames = new Vec3[frames][hitboxes.size()];
        for (int f = 0; f < frames; f++) {
            Xform[] pose = RigSampler.pose(rig, animation, ticks[f]);
            for (int p = 0; p < parts.size(); p++) {
                BakedRig.PartRef ref = parts.get(p);
                partFrames[p][f] = RigSampler.part(pose[ref.bone()], ref.part());
            }
            for (int h = 0; h < hitboxes.size(); h++) {
                BakedRig.HitboxRef ref = hitboxes.get(h);
                hitboxFrames[f][h] = RigSampler.hitboxCenter(pose[ref.bone()], ref.hitbox());
            }
        }
        List<BakedRig.KeyTrack> tracks = new ArrayList<>(parts.size());
        for (Xform[] values : partFrames) {
            tracks.add(compress(ticks, values, settings.tolerance(), settings.maxGap()));
        }
        return new BakedRig.BakedAnimation(
                name, length, mode, settings.ticksPerFrame(), List.copyOf(tracks), hitboxFrames);
    }

    /**
     * Reduces a frame list to keys. The first and last frames are always keys. Frames between two
     * keys are dropped if all are within tolerance of the keys' interpolation.
     */
    public static BakedRig.KeyTrack compress(
            int[] ticks, Xform[] values, Xform.Tolerance tolerance, int maxGap) {
        int n = ticks.length;
        List<Integer> keys = new ArrayList<>();
        keys.add(0);
        int a = 0;
        while (a < n - 1) {
            int best = a + 1;
            int limit = Math.min(n - 1, a + maxGap);
            for (int b = a + 2; b <= limit; b++) {
                if (!coverable(ticks, values, a, b, tolerance)) {
                    break;
                }
                best = b;
            }
            keys.add(best);
            a = best;
        }
        int[] keyTicks = new int[keys.size()];
        Xform[] keyValues = new Xform[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            keyTicks[i] = ticks[keys.get(i)];
            keyValues[i] = values[keys.get(i)];
        }
        return new BakedRig.KeyTrack(keyTicks, keyValues);
    }

    private static boolean coverable(
            int[] ticks, Xform[] values, int a, int b, Xform.Tolerance tolerance) {
        double span = ticks[b] - ticks[a];
        for (int j = a + 1; j < b; j++) {
            double f = (ticks[j] - ticks[a]) / span;
            if (!Xform.interpolate(values[a], values[b], f).near(values[j], tolerance)) {
                return false;
            }
        }
        return true;
    }
}
