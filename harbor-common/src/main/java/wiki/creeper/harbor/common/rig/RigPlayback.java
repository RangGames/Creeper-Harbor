package wiki.creeper.harbor.common.rig;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Playback state of one rig (pure computation). When {@link #update} is called every server tick,
 * it emits a "interpolate to this value over this many ticks" command for a part only when that
 * part enters a new key interval ('17-3' 9.4: interpolation commands toward the next key value are
 * sent only on key frames). The client fills the gap with Display interpolation.
 *
 * <p>Time is computed from the server tick number, so even if updates skip a few ticks (budget
 * exceeded, LOD), the next update lands in the right place.
 */
public final class RigPlayback {

    /** Command receiver (Core applies them to Display entities). */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param part part index (in {@link BakedRig#parts()} order)
         * @param target target pose (relative to the rig origin, excluding instance scale)
         * @param duration interpolation ticks (0 means immediately)
         */
        void send(int part, Xform target, int duration);
    }

    /** Animation event receiver (RigDef events). */
    @FunctionalInterface
    public interface EventSink {
        void fire(String animation, String event);
    }

    /**
     * If updates stop for longer than this and then resume (LOD, no viewers), events in between are
     * dropped (ticks).
     */
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

    /** Where animation events go. Called from {@link #update}. */
    public void setEventSink(@Nullable EventSink sink) {
        this.eventSink = sink;
    }

    /** Current animation. null in the rest pose. */
    public @Nullable String animation() {
        return current == rig.rest() ? null : current.name();
    }

    /**
     * Changes the animation. If the animation's mode is once, it returns to default_animation when
     * finished.
     *
     * @param blendTicks ticks to blend from the current pose into the new animation's first pose
     * @param startFrame start frame (−1 means 0)
     * @return false if there is no such animation
     */
    public boolean play(String name, int blendTicks, int startFrame, long now) {
        Optional<BakedRig.BakedAnimation> animation = rig.animation(name);
        if (animation.isEmpty()) {
            return false;
        }
        start(animation.get(), false, null, blendTicks, Math.max(0, startFrame), now);
        return true;
    }

    /** Plays once, then returns to returnTo (default_animation if absent). */
    public boolean playOnce(String name, @Nullable String returnTo, int blendTicks, long now) {
        Optional<BakedRig.BakedAnimation> animation = rig.animation(name);
        if (animation.isEmpty()) {
            return false;
        }
        start(animation.get(), true, returnTo, blendTicks, 0, now);
        return true;
    }

    /** Returns to the rest pose. */
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

    /**
     * Re-captures the current animation's event list and emits events from the start position on.
     */
    private void resetEvents() {
        RigDef.Animation def =
                current == rig.rest() ? null : rig.def().animations().get(current.name());
        events = def == null ? List.of() : def.events();
        eventTime = baseTime - 1e-6;
    }

    /** Emits events within (previous update time, t]. t is in {@link #time} terms. */
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

    /** Sets the playback position by frame. */
    public void setPhase(int frame, long now) {
        int frames = current.frameCount();
        int f;
        if (frames <= 1) {
            f = 0;
        } else if (loops()) {
            f =
                    Math.floorMod(
                            frame,
                            frames - 1); // the last frame of a looping animation equals the first
        } else {
            f = Math.max(0, Math.min(frame, frames - 1));
        }
        baseTick = now;
        baseTime = Math.min(current.length(), f * (double) current.ticksPerFrame());
        eventTime = baseTime - 1e-6;
        resync();
    }

    /** Current frame. 0 while blending. */
    public int frame(long now) {
        return (int) Math.floor(localTime(now) / current.ticksPerFrame());
    }

    public int frameCount() {
        return current.frameCount();
    }

    public double speed() {
        return speed;
    }

    /** Changes the speed (the playback position carries over). */
    public void setSpeed(double newSpeed, long now) {
        double t = time(now);
        if (t >= 0) {
            baseTime = t;
            baseTick = now;
        }
        speed = Math.max(0, newSpeed);
        resync();
    }

    /**
     * Resends all part commands on the next update (when returning from LOD or becoming newly
     * visible).
     */
    public void resync() {
        Arrays.fill(lastSegment, UNSET);
    }

    /**
     * Time since the animation started (ticks). Negative while blending (minus the remaining
     * ticks).
     */
    double time(long now) {
        if (now < baseTick) {
            return -(double) (baseTick - now);
        }
        return baseTime + (now - baseTick) * speed;
    }

    /** Position within the animation (0 ~ length). Looping animations are folded into one cycle. */
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

    /** Hitbox center (relative to the rig origin). */
    public Vec3 hitbox(int index, long now) {
        return current.hitbox(index, localTime(now));
    }

    /** Current pose of a part (server-side estimate). */
    public Xform pose(int part, long now) {
        return current.part(part).sample(localTime(now));
    }

    /** Emits this tick's commands. Moves on to the next animation when a one-shot has finished. */
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
        fireEvents(current.length()); // so events right before the end are not missed
        boolean returns = once || current.mode() == RigDef.LoopMode.ONCE;
        if (!returns) {
            return; // hold: keep the last pose
        }
        String next = returnTo != null ? returnTo : rig.def().defaultAnimation();
        Optional<BakedRig.BakedAnimation> animation =
                next == null ? Optional.empty() : rig.animation(next);
        if (animation.isEmpty()) {
            return; // no animation to return to: keep the last pose
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
