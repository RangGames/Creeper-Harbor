package wiki.creeper.harbor.boss.encounter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-encounter main thread tick cost for /harbor boss debug (docs 20 10.5 "tick cost", budget
 * 10.6: 0.5ms average per encounter). Keeps a moving average and the maximum of the last 200 ticks
 * (10 seconds). Main thread only.
 */
final class TickCosts {

    private static final int WINDOW = 200;
    private static final double ALPHA = 0.05;

    private static final class Cost {
        double avgNanos = -1;
        final long[] recent = new long[WINDOW];
        int next;
        long samples;
    }

    private final Map<UUID, Cost> costs = new HashMap<>();

    void record(UUID encounter, long nanos) {
        Cost c = costs.computeIfAbsent(encounter, k -> new Cost());
        c.avgNanos = c.avgNanos < 0 ? nanos : c.avgNanos + ALPHA * (nanos - c.avgNanos);
        c.recent[c.next] = nanos;
        c.next = (c.next + 1) % WINDOW;
        c.samples++;
    }

    void forget(UUID encounter) {
        costs.remove(encounter);
    }

    /** One debug line (average, 10-second max, sample count). */
    String describe(UUID encounter) {
        Cost c = costs.get(encounter);
        if (c == null) {
            return "tick cost: no samples";
        }
        long max = 0;
        for (long v : c.recent) {
            max = Math.max(max, v);
        }
        return String.format(
                Locale.ROOT,
                "tick cost avg=%.3fms max(10s)=%.3fms samples=%d (budget 0.5ms)",
                c.avgNanos / 1_000_000.0,
                max / 1_000_000.0,
                c.samples);
    }
}
