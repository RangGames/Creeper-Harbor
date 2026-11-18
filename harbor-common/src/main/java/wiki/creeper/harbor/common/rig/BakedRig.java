package wiki.creeper.harbor.common.rig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 재생용으로 미리 계산한 리그. 동작마다 부품별 압축 키프레임과 판정 상자 위치를 담는다. 변경 불가라 어느 스레드에서 읽어도 된다.
 *
 * @param parts 부품이 있는 뼈 (Display 엔티티 하나씩)
 * @param hitboxes 판정 상자가 있는 뼈 (Interaction 엔티티 하나씩)
 * @param rest 기본 자세 (길이 0, 키 하나)
 */
public record BakedRig(
        RigDef def,
        List<PartRef> parts,
        List<HitboxRef> hitboxes,
        Map<String, BakedAnimation> animations,
        BakedAnimation rest,
        int ticksPerFrame) {

    /** 부품 번호 → 뼈. */
    public record PartRef(int bone, String name, RigDef.Part part) {}

    /** 판정 상자 번호 → 뼈. */
    public record HitboxRef(int bone, String name, RigDef.Hitbox hitbox) {}

    public Optional<BakedAnimation> animation(String name) {
        return Optional.ofNullable(animations.get(name));
    }

    /** 동작 하나 (구운 결과). 길이는 틱, 부품 번호 순서의 키 목록과 [프레임][판정 상자] 상자 중심(리그 원점 기준)을 담는다. */
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

        /** 시각 t의 판정 상자 중심 (프레임 사이는 선형 보간). */
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

        /** 키 수 합계 (압축 효과 확인용). */
        public int keyCount() {
            int n = 0;
            for (KeyTrack track : parts) {
                n += track.size();
            }
            return n;
        }
    }

    /** 부품 하나의 압축된 키. 시각은 오름차순이고 첫 키는 0, 마지막 키는 동작 길이다. 키 사이는 클라이언트가 선형·구면 보간한다. */
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

        /** t가 들어 있는 구간의 시작 키 번호 (tick(i) ≤ t < tick(i+1)). 마지막 키 이후면 마지막 번호. */
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

        /** 시각 t의 값 (키 사이 보간, 클라이언트와 같은 방식). */
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
