package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * 최후의 줄다리기 QTE (docs 20 10.4). 라운드마다 플레이어별로 다른 입력 N개(W·A·S·D·점프·웅크리기)를 보여주고, 입력 하나당 제한 시간 안에 눌림
 * 변화(뗀 상태 → 누름)만 센다. 라운드 성공률 = 성공 입력 수 ÷ 전체 입력 수 (낙수·이탈 중인 사람은 분모에서 뺀다).
 */
public final class Qte {

    /** 입력 키. */
    public enum Key implements Keyed {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        JUMP,
        SNEAK;

        /** 조작 반전 디버프 (앞뒤·좌우가 뒤집힘). */
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

    /** 입력 상태 한 번 (PlayerInputEvent.getInput()을 옮긴 것). */
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

        /** 이전 상태에서 새로 눌린 키 (누르고 있기는 세지 않음). */
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

    /** 라운드 입력 순서 (같은 키 연속은 피한다: 누르고 있기와 구분이 안 되므로). */
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
     * 플레이어 한 명의 한 라운드 진행.
     *
     * <p>입력 하나마다 제한 시간 window (± tolerance 틱 여유, '8. 시스템 상세 기획' 3.3). 맞는 키를 누르면 성공, 다른 키를 누르면 실패,
     * 시간이 지나면 실패하고 다음 입력으로 넘어간다.
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

        /** 조작 반전 디버프 (표시는 원래 키, 실제로 눌러야 하는 키는 반대). */
        public void setInverted(boolean inverted) {
            this.inverted = inverted;
        }

        /** 낙수·이탈: 분모에서 뺀다. */
        public void setExcluded(boolean excluded) {
            this.excluded = excluded;
        }

        public boolean excluded() {
            return excluded;
        }

        /**
         * 새로 눌린 키를 넘긴다.
         *
         * @return 입력 하나가 판정됐으면 true
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

        /** 시간 초과 판정. */
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

        /** 지금 눌러야 하는 키 (표시용, 반전 전). 끝났으면 null 대신 마지막 키. */
        public Key current() {
            return keys.get(Math.min(index, keys.size() - 1));
        }

        public int index() {
            return index;
        }

        public List<Key> keys() {
            return keys;
        }

        /** 판정된 입력별 성공 여부 (표시용). */
        public List<Boolean> results() {
            return List.copyOf(results);
        }

        public int successes() {
            return successes;
        }

        public int total() {
            return keys.size();
        }

        /** 지금 입력의 남은 틱. */
        public long remaining(long now) {
            return Math.max(0, deadline - now);
        }
    }

    /** 라운드 성공률. 제외된 사람은 분모·분자에서 뺀다. 남은 사람이 없으면 0. */
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

    /** 모두 끝났는지 (제외된 사람은 기다리지 않음). */
    public static boolean allDone(Collection<Track> tracks) {
        for (Track t : tracks) {
            if (!t.excluded() && !t.done()) {
                return false;
            }
        }
        return true;
    }
}
