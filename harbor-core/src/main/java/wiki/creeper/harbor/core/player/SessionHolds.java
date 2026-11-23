package wiki.creeper.harbor.core.player;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-player in-flight work that must finish before the session lock is passed on ({@link
 * wiki.creeper.harbor.core.api.PlayerService#holdSession}). Thread-safe. Holds remove themselves
 * when their future completes.
 */
final class SessionHolds {

    /** Holds per player, keyed by a sequence number (futures have no defined equality). */
    private final Map<UUID, Map<Long, CompletableFuture<?>>> holds = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    void hold(UUID player, CompletableFuture<?> work) {
        if (work.isDone()) {
            return;
        }
        long id = sequence.incrementAndGet();
        // Add and remove inside compute so a map emptied by a completing hold is never reused
        holds.compute(
                player,
                (k, map) -> {
                    Map<Long, CompletableFuture<?>> next =
                            map == null ? new ConcurrentHashMap<>() : map;
                    next.put(id, work);
                    return next;
                });
        var _ =
                work.whenComplete(
                        (v, e) ->
                                holds.computeIfPresent(
                                        player,
                                        (k, map) -> {
                                            map.remove(id);
                                            return map.isEmpty() ? null : map;
                                        }));
    }

    /** Number of holds still pending for the player (for tests and logging). */
    int pending(UUID player) {
        Map<Long, CompletableFuture<?>> map = holds.get(player);
        return map == null ? 0 : map.size();
    }

    /**
     * Blocks until every hold registered so far for the player completes, or the timeout passes. A
     * hold that completes exceptionally counts as finished. Do not call on the main thread.
     *
     * @return true if all finished in time, false on timeout or interrupt
     */
    boolean await(UUID player, Duration timeout) {
        Map<Long, CompletableFuture<?>> map = holds.get(player);
        if (map == null) {
            return true;
        }
        // A failed or cancelled hold counts as finished
        CompletableFuture<?>[] settled =
                map.values().stream()
                        .map(f -> f.handle((v, e) -> null))
                        .toArray(CompletableFuture<?>[]::new);
        if (settled.length == 0) {
            return true;
        }
        try {
            CompletableFuture.allOf(settled).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
