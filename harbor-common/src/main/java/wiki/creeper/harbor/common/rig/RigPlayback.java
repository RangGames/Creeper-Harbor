package wiki.creeper.harbor.common.rig;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 리그 하나의 재생 상태 (순수 계산). 서버 틱마다 {@link #update}를 부르면, 부품마다 새 키 구간에 들어섰을 때만 "이 값까지 이 틱 동안 보간하라"는 명령을
 * 낸다 ('17-3' 9.4: 키 프레임에서만 다음 키 값으로 보간 명령을 보낸다). 클라이언트가 Display 보간으로 그 사이를 채운다.
 *
 * <p>시간은 서버 틱 번호로 계산하므로, 갱신을 몇 틱 건너뛰어도(예산 초과·LOD) 다음 갱신에서 제자리를 찾는다.
 */
public final class RigPlayback {

    /** 명령 받는 쪽 (Core가 Display 엔티티에 반영). */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param part 부품 번호 ({@link BakedRig#parts()} 순서)
         * @param target 도착 자세 (리그 원점 기준, 인스턴스 크기 미포함)
         * @param duration 보간 틱 (0이면 바로)
         */
        void send(int part, Xform target, int duration);
    }

    /** 동작 이벤트 받는 쪽 (RigDef events). */
    @FunctionalInterface
    public interface EventSink {
        void fire(String animation, String event);
    }

    /** 이보다 오래 갱신이 끊겼다가 돌아오면(LOD·시청자 없음) 그사이 이벤트는 버린다 (틱). */
    private static final double MAX_EVENT_GAP = 20;

    private static final long UNSET = Long.MIN_VALUE;
    private static final long BLEND = Long.MIN_VALUE + 1;
    private static final long FROZEN = Long.MIN_VALUE + 2;

    private final BakedRig rig;
    private final long[] lastSegment;
    private BakedRig.BakedAnimation current;
    private boolean once;
    private @Nullable String returnTo;
    private long baseTick;
    private double baseTime;
    private double speed = 1;
    private List<RigDef.Event> events = List.of();
    private double eventTime = Double.NaN;
    private @Nullable EventSink eventSink;

    public RigPlayback(BakedRig rig) {
        this.rig = rig;
        this.lastSegment = new long[rig.parts().size()];
        this.current = rig.rest();
        resync();
    }

    public BakedRig rig() {
        return rig;
    }

    /** 동작 이벤트를 받을 곳. {@link #update}에서 부른다. */
    public void setEventSink(@Nullable EventSink sink) {
        this.eventSink = sink;
    }

    /** 지금 동작. 기본 자세면 null. */
    public @Nullable String animation() {
        return current == rig.rest() ? null : current.name();
    }

    /**
     * 동작을 바꾼다. 동작의 mode가 once면 끝난 뒤 default_animation으로 돌아간다.
     *
     * @param blendTicks 지금 자세에서 새 동작 첫 자세로 이어 붙이는 틱
     * @param startFrame 시작 프레임 (−1이면 0)
     * @return 동작이 없으면 false
     */
    public boolean play(String name, int blendTicks, int startFrame, long now) {
        Optional<BakedRig.BakedAnimation> animation = rig.animation(name);
        if (animation.isEmpty()) {
            return false;
        }
        start(animation.get(), false, null, blendTicks, Math.max(0, startFrame), now);
        return true;
    }

    /** 한 번 재생한 뒤 returnTo(없으면 default_animation)로 돌아간다. */
    public boolean playOnce(String name, @Nullable String returnTo, int blendTicks, long now) {
        Optional<BakedRig.BakedAnimation> animation = rig.animation(name);
        if (animation.isEmpty()) {
            return false;
        }
        start(animation.get(), true, returnTo, blendTicks, 0, now);
        return true;
    }

    /** 기본 자세로 돌아간다. */
    public void stop(int blendTicks, long now) {
        start(rig.rest(), false, null, blendTicks, 0, now);
    }

    private void start(
            BakedRig.BakedAnimation animation,
            boolean forceOnce,
            @Nullable String next,
            int blendTicks,
            int startFrame,
            long now) {
        current = animation;
        once = forceOnce;
        returnTo = next;
        baseTick = now + Math.max(0, blendTicks);
        baseTime = Math.min(animation.length(), startFrame * (double) animation.ticksPerFrame());
        resetEvents();
        resync();
    }

    /** 지금 동작의 이벤트 목록을 다시 잡고, 시작 위치의 이벤트부터 내도록 한다. */
    private void resetEvents() {
        RigDef.Animation def =
                current == rig.rest() ? null : rig.def().animations().get(current.name());
        events = def == null ? List.of() : def.events();
        eventTime = baseTime - 1e-6;
    }

    /** (이전 갱신 시각, t]에 든 이벤트를 낸다. t는 {@link #time} 기준. */
    private void fireEvents(double t) {
        double prev = eventTime;
        eventTime = t;
        EventSink sink = eventSink;
        if (sink == null || events.isEmpty() || Double.isNaN(prev) || t <= prev) {
            return;
        }
        if (t - prev > MAX_EVENT_GAP) {
            return;
        }
        int length = current.length();
        for (RigDef.Event e : events) {
            double at;
            if (loops() && length > 0) {
                at = (Math.floor((prev - e.tick()) / length) + 1) * length + e.tick();
            } else {
                at = e.tick();
            }
            if (at > prev && at <= t && at >= 0) {
                sink.fire(current.name(), e.name());
            }
        }
    }

    /** 재생 위치를 프레임으로 맞춘다. */
    public void setPhase(int frame, long now) {
        int frames = current.frameCount();
        int f;
        if (frames <= 1) {
            f = 0;
        } else if (loops()) {
            f = Math.floorMod(frame, frames - 1); // 반복 동작의 마지막 프레임은 첫 프레임과 같다
        } else {
            f = Math.max(0, Math.min(frame, frames - 1));
        }
        baseTick = now;
        baseTime = Math.min(current.length(), f * (double) current.ticksPerFrame());
        eventTime = baseTime - 1e-6;
        resync();
    }

    /** 지금 프레임. 이어 붙이는 중이면 0. */
    public int frame(long now) {
        return (int) Math.floor(localTime(now) / current.ticksPerFrame());
    }

    public int frameCount() {
        return current.frameCount();
    }

    public double speed() {
        return speed;
    }

    /** 속도를 바꾼다 (재생 위치는 이어진다). */
    public void setSpeed(double newSpeed, long now) {
        double t = time(now);
        if (t >= 0) {
            baseTime = t;
            baseTick = now;
        }
        speed = Math.max(0, newSpeed);
        resync();
    }

    /** 다음 갱신 때 모든 부품 명령을 다시 보낸다 (LOD에서 돌아왔을 때, 새로 보이게 됐을 때). */
    public void resync() {
        Arrays.fill(lastSegment, UNSET);
    }

    /** 동작 시작부터의 시간 (틱). 이어 붙이는 중이면 음수 (남은 틱의 음수). */
    double time(long now) {
        if (now < baseTick) {
            return -(double) (baseTick - now);
        }
        return baseTime + (now - baseTick) * speed;
    }

    /** 동작 안의 위치 (0 ~ 길이). 반복 동작은 한 바퀴로 접는다. */
    public double localTime(long now) {
        double t = time(now);
        int length = current.length();
        if (t <= 0 || length <= 0) {
            return 0;
        }
        if (loops()) {
            return t - Math.floor(t / length) * length;
        }
        return Math.min(t, length);
    }

    private boolean loops() {
        return !once && current.mode() == RigDef.LoopMode.LOOP;
    }

    /** 판정 상자 중심 (리그 원점 기준). */
    public Vec3 hitbox(int index, long now) {
        return current.hitbox(index, localTime(now));
    }

    /** 부품의 지금 자세 (서버 쪽 추정값). */
    public Xform pose(int part, long now) {
        return current.part(part).sample(localTime(now));
    }

    /** 이번 틱 명령을 낸다. 한 번 재생이 끝났으면 다음 동작으로 넘어간다. */
    public void update(long now, Sink sink) {
        finishIfDone(now);
        double t = time(now);
        fireEvents(t);
        int length = current.length();
        for (int p = 0; p < lastSegment.length; p++) {
            BakedRig.KeyTrack keys = current.part(p);
            long segment;
            Xform target;
            int duration;
            if (t < 0) {
                segment = BLEND;
                target = keys.value(0);
                duration = (int) Math.ceil(-t);
            } else if (speed <= 0) {
                segment = FROZEN;
                target = keys.sample(localTime(now));
                duration = 0;
            } else {
                long cycle = 0;
                double local;
                if (length <= 0) {
                    local = 0;
                } else if (loops()) {
                    cycle = (long) Math.floor(t / length);
                    local = t - cycle * (double) length;
                } else {
                    local = Math.min(t, length);
                }
                int i = keys.segment(local);
                int last = keys.size() - 1;
                if (i >= last) {
                    segment = cycle * keys.size() + last;
                    target = keys.value(last);
                    duration = 0;
                } else {
                    segment = cycle * keys.size() + i;
                    target = keys.value(i + 1);
                    double remaining = keys.tick(i + 1) - local;
                    duration = Math.max(1, (int) Math.round(remaining / speed));
                }
            }
            if (segment != lastSegment[p]) {
                lastSegment[p] = segment;
                sink.send(p, target, duration);
            }
        }
    }

    private void finishIfDone(long now) {
        if (loops() || current == rig.rest()) {
            return;
        }
        double t = time(now);
        if (t < current.length()) {
            return;
        }
        fireEvents(current.length()); // 끝나기 직전 이벤트를 빠뜨리지 않게
        boolean returns = once || current.mode() == RigDef.LoopMode.ONCE;
        if (!returns) {
            return; // hold: 마지막 자세 유지
        }
        String next = returnTo != null ? returnTo : rig.def().defaultAnimation();
        Optional<BakedRig.BakedAnimation> animation =
                next == null ? Optional.empty() : rig.animation(next);
        if (animation.isEmpty()) {
            return; // 돌아갈 동작이 없으면 마지막 자세 유지
        }
        double overshoot = t - current.length();
        current = animation.get();
        once = false;
        returnTo = null;
        baseTick = now;
        baseTime = Math.min(current.length(), overshoot);
        resetEvents();
        eventTime = -1e-6;
        resync();
    }
}
