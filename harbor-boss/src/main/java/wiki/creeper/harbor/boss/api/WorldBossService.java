package wiki.creeper.harbor.boss.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.id.BossId;

/**
 * World boss (docs 20 10.4). Quest decides the climax unlock condition and calls {@link #start}.
 * Only the one server that acquires harbor:lock:worldboss runs it, and progress is broadcast to all
 * servers on harbor:worldboss every 2 seconds. Call on the main thread; result futures also
 * complete on the main thread.
 */
public interface WorldBossService {

    /** Result. */
    enum StartResult {
        STARTED,
        /** Another server is running it (lock not acquired). */
        LOCKED,
        ALREADY_RUNNING,
        UNKNOWN_BOSS,
        /** The arena world is not on this server. */
        NO_ARENA,
        FAILED
    }

    /**
     * Most recently received progress status (received via broadcast even if this server is not
     * running it).
     */
    Optional<WorldBossStatus> status();

    /** Whether it is running on this server. */
    boolean runningHere();

    CompletableFuture<StartResult> start(BossId boss);
}
