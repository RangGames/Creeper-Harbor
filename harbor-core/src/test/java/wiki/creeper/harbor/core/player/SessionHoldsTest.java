package wiki.creeper.harbor.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SessionHoldsTest {

    private final SessionHolds holds = new SessionHolds();
    private final UUID player = UUID.randomUUID();

    @Test
    void noHoldsReturnsAtOnce() {
        assertTrue(holds.await(player, Duration.ZERO));
    }

    @Test
    void holdsRemoveThemselvesWhenDone() {
        CompletableFuture<Void> work = new CompletableFuture<>();
        holds.hold(player, work);
        assertEquals(1, holds.pending(player));
        work.complete(null);
        assertEquals(0, holds.pending(player));
    }

    @Test
    void timesOutWhileWorkIsPending() {
        holds.hold(player, new CompletableFuture<>());
        assertFalse(holds.await(player, Duration.ofMillis(50)));
    }

    @Test
    void waitsForWorkFinishingLater() {
        CompletableFuture<Void> work = new CompletableFuture<>();
        holds.hold(player, work);
        var _ =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            work.complete(null);
                        });
        assertTrue(holds.await(player, Duration.ofSeconds(5)));
    }

    @Test
    void failedWorkCountsAsFinished() {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        CompletableFuture<Void> ok = new CompletableFuture<>();
        holds.hold(player, failed);
        holds.hold(player, ok);
        failed.completeExceptionally(new IllegalStateException("db down"));
        ok.complete(null);
        assertTrue(holds.await(player, Duration.ofMillis(100)));
    }

    @Test
    void otherPlayersAreIndependent() {
        holds.hold(UUID.randomUUID(), new CompletableFuture<>());
        assertTrue(holds.await(player, Duration.ZERO));
    }
}
