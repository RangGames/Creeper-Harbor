package wiki.creeper.harbor.skript.bridge;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * Eventually consistent view of an async (DB) lookup for Skript expressions, which run on the main
 * thread and must not wait. A read returns the last loaded value (none the first time) and starts a
 * reload in the background when the value is older than balance.yml {@code skript.cache_seconds}.
 * Same behaviour as {@code harbor gold} right after a change: read again a little later.
 */
final class AsyncCache<K, V> {

    /** Entry cap per cache (proposed value). Cleared all at once when exceeded. */
    private static final int MAX_ENTRIES = 512;

    private record Entry<V>(@Nullable V value, long loadedAt, boolean loading) {}

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Function<K, CompletableFuture<V>> loader;

    AsyncCache(Function<K, CompletableFuture<V>> loader) {
        this.loader = loader;
    }

    /** Last loaded value (null if never loaded). Starts a reload if stale. */
    @Nullable V get(K key) {
        long now = System.currentTimeMillis();
        Entry<V> e = entries.get(key);
        if (e == null || (!e.loading() && now - e.loadedAt() > ttlMillis())) {
            load(key, e);
        }
        return e == null ? null : e.value();
    }

    private void load(K key, @Nullable Entry<V> old) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        V previous = old == null ? null : old.value();
        entries.put(key, new Entry<>(previous, old == null ? 0 : old.loadedAt(), true));
        CompletableFuture<V> f;
        try {
            f = loader.apply(key);
        } catch (RuntimeException ex) {
            entries.put(key, new Entry<>(previous, System.currentTimeMillis(), false));
            return;
        }
        var _ =
                f.whenComplete(
                        (v, err) ->
                                entries.put(
                                        key,
                                        new Entry<>(
                                                err == null ? v : previous,
                                                System.currentTimeMillis(),
                                                false)));
    }

    private static long ttlMillis() {
        Balance b = Harbor.api().content().snapshot().single(Balance.TYPE);
        return Math.round(b.number("skript.cache_seconds", 30) * 1000);
    }
}
