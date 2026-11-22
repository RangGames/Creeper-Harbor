package wiki.creeper.harbor.skript.bridge;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * Eventually consistent view of an async Harbor API (DB) call for Skript expressions, which run on
 * the main thread and must not wait. A read returns the last loaded value (none the first time) and
 * starts a reload in the background when the value is stale. A script therefore reads the value
 * from the previous load; reading again a little later sees the new one (same behaviour as {@code
 * harbor gold} right after a change).
 *
 * <p>Two refresh policies, kept as they were before the two caches were merged:
 *
 * <ul>
 *   <li>{@link #ttlFromBalance}: stale after balance.yml {@code skript.cache_seconds} (default 30,
 *       read on every check), at most {@value #MAX_ENTRIES} entries (cleared all at once when
 *       exceeded). A failed load keeps the value from before that load.
 *   <li>{@link #every}: stale after a fixed interval, no entry cap. A loader that throws right away
 *       clears the value; a load that completes with an error keeps the value present at that
 *       moment.
 * </ul>
 */
final class AsyncCache<K, V> {

    /** Entry cap for {@link #ttlFromBalance} caches (proposed value). */
    private static final int MAX_ENTRIES = 512;

    private record Entry<V>(@Nullable V value, long loadedAt, boolean loading) {}

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Function<K, CompletableFuture<V>> loader;

    /** A value is stale once it is older than this many milliseconds (strictly). */
    private final LongSupplier staleAfterMillis;

    private final int maxEntries;

    /** True for {@link #every} (fixed interval) semantics on failure, false for TTL ones. */
    private final boolean fixed;

    private AsyncCache(
            Function<K, CompletableFuture<V>> loader,
            LongSupplier staleAfterMillis,
            int maxEntries,
            boolean fixed) {
        this.loader = loader;
        this.staleAfterMillis = staleAfterMillis;
        this.maxEntries = maxEntries;
        this.fixed = fixed;
    }

    /** Reloads after balance.yml {@code skript.cache_seconds}; capped at {@value #MAX_ENTRIES}. */
    static <K, V> AsyncCache<K, V> ttlFromBalance(Function<K, CompletableFuture<V>> loader) {
        return new AsyncCache<>(loader, AsyncCache::balanceTtlMillis, MAX_ENTRIES, false);
    }

    /** Reloads when the value is at least refreshMillis old; no entry cap. */
    static <K, V> AsyncCache<K, V> every(
            long refreshMillis, Function<K, CompletableFuture<V>> loader) {
        // "age >= refreshMillis" is "age > refreshMillis - 1" for whole milliseconds.
        long staleAfter = refreshMillis - 1;
        return new AsyncCache<>(loader, () -> staleAfter, Integer.MAX_VALUE, true);
    }

    /** Last loaded value (null if never loaded or none). Starts a reload if stale. */
    @Nullable V get(K key) {
        long now = System.currentTimeMillis();
        Entry<V> e = entries.get(key);
        if (e == null || (!e.loading() && now - e.loadedAt() > staleAfterMillis.getAsLong())) {
            load(key, e);
        }
        return e == null ? null : e.value();
    }

    /** {@link #get} as an Optional. */
    Optional<V> latest(K key) {
        return Optional.ofNullable(get(key));
    }

    /** Every cached value (no reload). */
    Iterable<V> cached() {
        return entries.values().stream().map(Entry::value).filter(Objects::nonNull).toList();
    }

    /** Starts a reload now (e.g. after a script changed the underlying data). */
    void refresh(K key) {
        load(key, entries.get(key));
    }

    private void load(K key, @Nullable Entry<V> old) {
        if (entries.size() >= maxEntries) {
            entries.clear();
        }
        V previous = old == null ? null : old.value();
        entries.put(key, new Entry<>(previous, old == null ? 0 : old.loadedAt(), true));
        CompletableFuture<V> f;
        try {
            f = loader.apply(key);
        } catch (RuntimeException ex) {
            entries.put(
                    key, new Entry<>(fixed ? null : previous, System.currentTimeMillis(), false));
            return;
        }
        var _ =
                f.whenComplete(
                        (v, err) -> {
                            V keep = previous;
                            if (fixed) {
                                Entry<V> current = entries.get(key);
                                keep = current == null ? null : current.value();
                            }
                            entries.put(
                                    key,
                                    new Entry<>(
                                            err == null ? v : keep,
                                            System.currentTimeMillis(),
                                            false));
                        });
    }

    private static long balanceTtlMillis() {
        Balance b = Harbor.api().content().snapshot().single(Balance.TYPE);
        return Math.round(b.number("skript.cache_seconds", 30) * 1000);
    }
}
