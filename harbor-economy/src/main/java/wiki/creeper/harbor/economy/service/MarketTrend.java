package wiki.creeper.harbor.economy.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Market board trend calculation (proposed, no server API). Compares the factor 24 hours ago with
 * the current one.
 */
public final class MarketTrend {

    /** Direction. */
    public enum Direction {
        UP,
        DOWN,
        FLAT
    }

    private MarketTrend() {}

    /** Now − 24 hours ago. 0 if there is no previous value. */
    public static double change(double now, double before) {
        return Double.isNaN(before) ? 0 : now - before;
    }

    /**
     * @param flat changes at or below this (absolute value) count as flat
     */
    public static Direction direction(double change, double flat) {
        if (change > flat) {
            return Direction.UP;
        }
        if (change < -flat) {
            return Direction.DOWN;
        }
        return Direction.FLAT;
    }

    /** Top n by size of change (flat excluded). */
    public static Set<String> topMovers(Map<String, Double> changes, int n, double flat) {
        return changes.entrySet().stream()
                .filter(e -> Math.abs(e.getValue()) > flat)
                .sorted(
                        Comparator.comparingDouble(
                                        (Map.Entry<String, Double> e) -> -Math.abs(e.getValue()))
                                .thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, n))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** First value of the 24-hour record (oldest first). NaN if none. */
    public static double oldest(List<Double> history) {
        return history.isEmpty() ? Double.NaN : history.getFirst();
    }
}
