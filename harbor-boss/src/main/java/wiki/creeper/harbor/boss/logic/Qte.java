package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * Final tug-of-war QTE (docs 20 10.4). Each round shows each player a different sequence of N
 * inputs (W, A, S, D, jump, sneak), and only press transitions (released → pressed) within each
 * input's time limit count. Round success rate = successful inputs ÷ total inputs (players who fell
 * into the water or left are excluded from the denominator).
 */
public final class Qte {

    /** Input key. */
    public enum Key implements Keyed {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        JUMP,
        SNEAK;

        /** Control reversal debuff (forward/back and left/right are flipped). */
        public Key inverted() {
            return switch (this) {
                case FORWARD -> BACKWARD;
                case BACKWARD -> FORWARD;
                case LEFT -> RIGHT;
                case RIGHT -> LEFT;
                case JUMP, SNEAK -> this;
            };
        }

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** One input state (a copy of PlayerInputEvent.getInput()). */
    public record InputState(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak) {

        public static final InputState NONE =
                new InputState(false, false, false, false, false, false);

        public boolean pressed(Key key) {
            return switch (key) {
                case FORWARD -> forward;
                case BACKWARD -> backward;
                case LEFT -> left;
                case RIGHT -> right;
                case JUMP -> jump;
                case SNEAK -> sneak;
            };
        }

        /** Keys newly pressed since the previous state (holding is not counted). */
        public Set<Key> newlyPressed(InputState previous) {
            Set<Key> out = EnumSet.noneOf(Key.class);
            for (Key k : Key.values()) {
                if (pressed(k) && !previous.pressed(k)) {
                    out.add(k);
                }
            }
            return out;
        }
    }

    private Qte() {}

    /**
     * Round input sequence (avoids the same key twice in a row, since it cannot be told apart from
     * holding).
     */
    public static List<Key> sequence(SplittableRandom random, int count) {
        Key[] keys = Key.values();
        List<Key> out = new ArrayList<>(count);
        Key prev = null;
        for (int i = 0; i < count; i++) {
            Key k;
            do {
                k = keys[random.nextInt(keys.length)];
            } while (k == prev);
            out.add(k);
            prev = k;
        }
        return List.copyOf(out);
    }

    /**
     * One player's progress in one round.
     *
     * <p>Each input has a time limit window (± tolerance ticks of slack, '8. System details' 3.3).
     * Pressing the right key succeeds, pressing another key fails, and running out of time fails
     * and moves on to the next input.
     */
    public static final class Track {

        private final List<Key> keys;
        private final int window;
        private final int tolerance;
        private int index;
        private int successes;
        private long deadline;
        private boolean inverted;
        private boolean excluded;
        private final List<Boolean> results = new ArrayList<>();

        public Track(List<Key> keys, int windowTicks, int toleranceTicks, long now) {
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("입력이 없습니다");
            }
            this.keys = List.copyOf(keys);
            this.window = Math.max(1, windowTicks);
            this.tolerance = Math.max(0, toleranceTicks);
            this.deadline = now + window;
        }

        /**
         * Control reversal debuff (the original key is displayed; the key actually required is the
         * opposite).
         */
        public void setInverted(boolean inverted) {
            this.inverted = inverted;
        }

        /** Fell into the water or left: excluded from the denominator. */
        public void setExcluded(boolean excluded) {
            this.excluded = excluded;
        }

        public boolean excluded() {
            return excluded;
        }

        /**
         * Passes newly pressed keys.
         *
         * @return true if one input was judged
         */
        public boolean press(Set<Key> newlyPressed, long now) {
            if (done() || newlyPressed.isEmpty()) {
                return false;
            }
            Key expected = inverted ? keys.get(index).inverted() : keys.get(index);
            boolean ok = newlyPressed.contains(expected);
            if (ok) {
                successes++;
            }
            results.add(ok);
            advance(now);
            return true;
        }

        /** Timeout judgment. */
        public void tick(long now) {
            if (!done() && now > deadline + tolerance) {
                results.add(false);
                advance(now);
            }
        }

        private void advance(long now) {
            index++;
            deadline = now + window;
        }

        public boolean done() {
            return index >= keys.size();
        }

        /**
         * Key to press now (for display, before reversal). The last key instead of null when
         * finished.
         */
        public Key current() {
            return keys.get(Math.min(index, keys.size() - 1));
        }

        public int index() {
            return index;
        }

        public List<Key> keys() {
            return keys;
        }

        /** Success of each judged input (for display). */
        public List<Boolean> results() {
            return List.copyOf(results);
        }

        public int successes() {
            return successes;
        }

        public int total() {
            return keys.size();
        }

        /** Ticks left for the current input. */
        public long remaining(long now) {
            return Math.max(0, deadline - now);
        }
    }

    /**
     * Round success rate. Excluded players are removed from both numerator and denominator. 0 if no
     * one remains.
     */
    public static double rate(Collection<Track> tracks) {
        int ok = 0;
        int total = 0;
        for (Track t : tracks) {
            if (t.excluded()) {
                continue;
            }
            ok += t.successes();
            total += t.total();
        }
        return total == 0 ? 0 : (double) ok / total;
    }

    /** Whether everyone has finished (excluded players are not waited for). */
    public static boolean allDone(Collection<Track> tracks) {
        for (Track t : tracks) {
            if (!t.excluded() && !t.done()) {
                return false;
            }
        }
        return true;
    }
}
