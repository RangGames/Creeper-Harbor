package wiki.creeper.harbor.web.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.web.relay.Globs;

/** 최근 외부 이벤트 (GET /v1/events). 들어온 순서 번호(seq)를 붙여 after로 이어 받게 한다. 스레드 안전. */
public final class RecentEvents {

    /** seq + 이벤트. */
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

    /** after보다 뒤의 것 (type glob이 있으면 거름), 오래된 것부터 limit개. */
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
