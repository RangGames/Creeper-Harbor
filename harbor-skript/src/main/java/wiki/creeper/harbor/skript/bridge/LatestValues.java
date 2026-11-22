package wiki.creeper.harbor.skript.bridge;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Last known value of an async Harbor API call, for Skript expressions that must not block the main
 * thread. Reading returns the cached value (empty the first time) and starts a background refresh
 * when the value is older than the refresh interval. Values are therefore eventually consistent: a
 * script reads the value from the previous refresh (at most a few seconds old).
 */
final class LatestValues<K, V> {

    private record Slot<V>(Optional<V> value, long fetchedAt, boolean loading) {}

    private final Map<K, Slot<V>> slots = new ConcurrentHashMap<>();
    private final Function<K, CompletableFuture<V>> loader;
    private final long refreshMillis;

    LatestValues(long refreshMillis, Function<K, CompletableFuture<V>> loader) {
        this.refreshMillis = refreshMillis;
        this.loader = loader;
    }

    /** The cached value; refreshes in the background if stale. */
    Optional<V> get(K key) {
        Slot<V> slot = slots.get(key);
        long now = System.currentTimeMillis();
        if (slot == null || (!slot.loading() && now - slot.fetchedAt() >= refreshMillis)) {
            refresh(key);
        }
        return slot == null ? Optional.empty() : slot.value();
    }

    /** Every cached value (no refresh). */
    Iterable<V> cached() {
        return slots.values().stream().flatMap(s -> s.value().stream()).toList();
    }

    /** Starts a refresh now (e.g. after a script changed the underlying data). */
    void refresh(K key) {
        Slot<V> old = slots.get(key);
        slots.put(
                key,
                new Slot<>(
                        old == null ? Optional.empty() : old.value(),
                        old == null ? 0 : old.fetchedAt(),
                        true));
        CompletableFuture<V> future;
        try {
            future = loader.apply(key);
        } catch (RuntimeException e) {
            slots.put(key, new Slot<>(Optional.empty(), System.currentTimeMillis(), false));
            return;
        }
        var _ =
                future.whenComplete(
                        (value, error) -> {
                            Slot<V> prev = slots.get(key);
                            Optional<V> keep = prev == null ? Optional.empty() : prev.value();
                            slots.put(
                                    key,
                                    new Slot<>(
                                            error == null ? Optional.ofNullable(value) : keep,
                                            System.currentTimeMillis(),
                                            false));
                        });
    }
}
