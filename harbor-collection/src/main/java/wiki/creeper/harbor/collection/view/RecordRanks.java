package wiki.creeper.harbor.collection.view;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import wiki.creeper.harbor.collection.api.RecordEntry;

/**
 * Biggest-catch rank computation (proposed, no server API). The server record cache holds the top
 * 10 per league, sorted by size descending, earlier catcher first.
 */
public final class RecordRanks {

    private RecordRanks() {}

    /** The player's rank within the top list (from 1; empty if absent). */
    public static OptionalInt rank(List<RecordEntry> top, UUID player) {
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).player().equals(player)) {
                return OptionalInt.of(i + 1);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Size (cm) still needed to enter the top list. 0 if the list is not full, and 0 if already in
     * it or above.
     *
     * @param capacity list size (server cache 10)
     */
    public static OptionalDouble gapToTop(List<RecordEntry> top, double mySize, int capacity) {
        if (top.size() < capacity) {
            return OptionalDouble.of(0);
        }
        double last = top.getLast().size();
        return OptionalDouble.of(Math.max(0, Math.nextUp(last) - mySize));
    }

    /**
     * Rank this size would get (ties go after, since the earlier catcher wins). Empty if beyond
     * capacity.
     */
    public static OptionalInt wouldRank(List<RecordEntry> top, double size, int capacity) {
        int position = 1;
        for (RecordEntry e : top) {
            if (e.size() >= size) {
                position++;
            }
        }
        return position <= capacity ? OptionalInt.of(position) : OptionalInt.empty();
    }
}
