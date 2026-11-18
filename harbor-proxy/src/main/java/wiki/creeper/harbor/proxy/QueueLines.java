package wiki.creeper.harbor.proxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * 대기열 줄 3개와 입장 순서 (docs 20 1.4 QueueManager 표). 서버 API 없는 순수 로직이라 단위 테스트한다. 동기화는 쓰는
 * 쪽(QueueManager)이 한다.
 *
 * <ul>
 *   <li>입장 순서: 재접속 유예 → 우선(후원자·운영진) → 일반
 *   <li>일반 줄이 굶지 않도록 입장 normal_share명(4, 제안값) 중 최소 1명은 일반 줄에서. 즉 일반 줄이 아닌 입장이 share − 1번 이어지면, 일반
 *       줄에 입장 가능한 사람이 있을 때 다음 차례는 일반 줄이다
 *   <li>아직 들어갈 수 없는 사람(이전 접속 정리 중)은 자리를 지킨 채 건너뛴다
 * </ul>
 *
 * 표시 순번은 줄 순서대로 이어 붙인 위치다 (일반 줄 보장분은 예상 시간에만 반영되고 순번에는 반영하지 않는다).
 */
final class QueueLines<T> {

    enum Line {
        GRACE,
        PRIORITY,
        NORMAL
    }

    private final Map<Line, Deque<T>> lines = new EnumMap<>(Line.class);
    private final int normalShare;
    private int sinceNormal;

    QueueLines(int normalShare) {
        if (normalShare < 1) {
            throw new IllegalArgumentException("normal_share는 1 이상: " + normalShare);
        }
        this.normalShare = normalShare;
        for (Line line : Line.values()) {
            lines.put(line, new ArrayDeque<>());
        }
    }

    /** 줄 끝에 넣는다. 이미 어딘가에 있으면 그대로 두고 false. */
    boolean add(Line line, T item) {
        if (contains(item)) {
            return false;
        }
        deque(line).addLast(item);
        return true;
    }

    /** 줄 맨 앞에 넣는다 (이미 있으면 옮긴다). */
    void addFront(Line line, T item) {
        remove(item);
        deque(line).addFirst(item);
    }

    boolean remove(T item) {
        boolean removed = false;
        for (Deque<T> deque : lines.values()) {
            removed |= deque.remove(item);
        }
        return removed;
    }

    boolean contains(T item) {
        return lines.values().stream().anyMatch(d -> d.contains(item));
    }

    Optional<Line> lineOf(T item) {
        for (Line line : Line.values()) {
            if (deque(line).contains(item)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /** 1부터 세는 전체 순번. */
    OptionalInt position(T item) {
        int index = 1;
        for (Line line : Line.values()) {
            for (T queued : deque(line)) {
                if (queued.equals(item)) {
                    return OptionalInt.of(index);
                }
                index++;
            }
        }
        return OptionalInt.empty();
    }

    int size() {
        return lines.values().stream().mapToInt(Deque::size).sum();
    }

    int size(Line line) {
        return deque(line).size();
    }

    List<T> snapshot() {
        List<T> all = new ArrayList<>();
        for (Line line : Line.values()) {
            all.addAll(deque(line));
        }
        return all;
    }

    /**
     * 전체 순번 position(1부터)으로 옮긴다 (/hp queue move). 그 순번이 속한 줄로 들어가며, 순번이 전체보다 크면 일반 줄 끝.
     *
     * @return 대기열에 없으면 false
     */
    boolean moveTo(T item, int position) {
        if (!remove(item)) {
            return false;
        }
        int index = Math.max(0, position - 1);
        for (Line line : Line.values()) {
            Deque<T> deque = deque(line);
            if (index < deque.size()) {
                insertAt(deque, index, item);
                return true;
            }
            index -= deque.size();
        }
        deque(Line.NORMAL).addLast(item);
        return true;
    }

    /**
     * 다음 입장자를 꺼낸다.
     *
     * @param ready 지금 들어갈 수 있는지. false인 사람은 자리를 지킨 채 건너뛴다
     */
    @Nullable T pollNext(Predicate<T> ready) {
        boolean normalReady = firstReady(Line.NORMAL, ready) != null;
        boolean forceNormal = normalReady && sinceNormal >= normalShare - 1;
        if (!forceNormal) {
            for (Line line : List.of(Line.GRACE, Line.PRIORITY)) {
                T next = takeFirstReady(line, ready);
                if (next != null) {
                    sinceNormal++;
                    return next;
                }
            }
        }
        T next = takeFirstReady(Line.NORMAL, ready);
        if (next != null) {
            sinceNormal = 0;
        }
        return next;
    }

    private @Nullable T firstReady(Line line, Predicate<T> ready) {
        for (T item : deque(line)) {
            if (ready.test(item)) {
                return item;
            }
        }
        return null;
    }

    private @Nullable T takeFirstReady(Line line, Predicate<T> ready) {
        Iterator<T> it = deque(line).iterator();
        while (it.hasNext()) {
            T item = it.next();
            if (ready.test(item)) {
                it.remove();
                return item;
            }
        }
        return null;
    }

    private static <T> void insertAt(Deque<T> deque, int index, T item) {
        List<T> copy = new ArrayList<>(deque);
        copy.add(Math.min(index, copy.size()), item);
        deque.clear();
        deque.addAll(copy);
    }

    private Deque<T> deque(Line line) {
        return Objects.requireNonNull(lines.get(line));
    }
}
