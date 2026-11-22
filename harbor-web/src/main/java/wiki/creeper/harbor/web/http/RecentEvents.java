package wiki.creeper.harbor.web.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.web.relay.Globs;

/**
 * Recent external events (GET /v1/events). Tags each with an arrival sequence number (seq) so
 * clients can continue with after. Thread-safe.
 */
public final class RecentEvents {

    /** seq + event. */
    public record Item(long seq, ExternalEvent event) {}

    private final int capacity;
    private final Deque<Item> items = new ArrayDeque<>();
    private long seq;

    public RecentEvents(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    public synchronized void add(ExternalEvent event) {
        items.addLast(new Item(++seq, event));
        while (items.size() > capacity) {
            items.removeFirst();
        }
    }

    /** Events after the given seq (filtered by type glob if given), oldest first, up to limit. */
    public synchronized List<Item> after(long after, String typeGlob, int limit) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (i.seq() > after
                    && (typeGlob.isEmpty() || Globs.matches(typeGlob, i.event().type()))) {
                out.add(i);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    public synchronized long last() {
        return seq;
    }
}
