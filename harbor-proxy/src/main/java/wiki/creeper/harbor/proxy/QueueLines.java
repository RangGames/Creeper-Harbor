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
 * The 3 queue lines and entry order (docs 20 1.4 QueueManager table). Pure logic without server
 * API, so it is unit-tested. Synchronization is done by the user (QueueManager).
 *
 * <ul>
 *   <li>Entry order: reconnect grace → priority (donors, staff) → normal
 *   <li>So the normal line does not starve, at least 1 of every normal_share entries (4, proposed
 *       value) comes from the normal line. That is, after share − 1 consecutive non-normal entries,
 *       the next turn goes to the normal line when someone there can enter
 *   <li>People who cannot enter yet (previous session still being cleaned up) are skipped while
 *       keeping their place
 * </ul>
 *
 * The displayed position is the position across the lines concatenated in order (the normal-line
 * guarantee only affects the estimated time, not the position).
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

    /** Appends to the end of a line. If already somewhere, leaves it and returns false. */
    boolean add(Line line, T item) {
        if (contains(item)) {
            return false;
        }
        deque(line).addLast(item);
        return true;
    }

    /** Puts at the front of a line (moves it if already present). */
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

    /** Overall position counting from 1. */
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
     * Moves to overall position (from 1) (/hp queue move). Joins the line that position belongs to;
     * if position exceeds the total, the end of the normal line.
     *
     * @return false if not in the queue
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
     * Takes the next entrant.
     *
     * @param ready whether they can enter now. Those returning false are skipped while keeping
     *     their place
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
