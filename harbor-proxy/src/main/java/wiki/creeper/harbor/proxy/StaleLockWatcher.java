package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.session.LockState;
import wiki.creeper.harbor.common.session.SessionLocks;

/**
 * Waiting for previous session cleanup (docs 20 1.4 "when a lock remains"). If the previous server
 * crashed and the session lock is still alive (up to 30 seconds), the queue skips that person, and
 * the lock is rechecked every second so they enter as soon as it is released. If waiting longer
 * than the lock TTL (abnormal case where the lock keeps being extended), the mark is cleared and
 * the decision is left to the main server Core's awaitClaim.
 */
final class StaleLockWatcher {

    private static final Duration GIVE_UP = SessionLocks.TTL.plusSeconds(10);

    private final ProxyServer proxy;
    private final SessionLocks locks;
    private final QueueManager queue;
    private final String lobby;
    private final Logger log;
    private final Map<UUID, Instant> since = new ConcurrentHashMap<>();

    StaleLockWatcher(
            ProxyServer proxy, SessionLocks locks, QueueManager queue, String lobby, Logger log) {
        this.proxy = proxy;
        this.locks = locks;
        this.queue = queue;
        this.lobby = lobby;
        this.log = log;
    }

    /**
     * At login (virtual thread): marks the player if a leftover lock exists. Not marked on Redis
     * error (Core checks again on entry).
     */
    void checkAtLogin(UUID player) {
        if (held(player)) {
            since.put(player, Instant.now());
            queue.markCleaning(player);
        }
    }

    /** Every second (scheduler thread; synchronous Redis calls allowed). */
    void tick() {
        Instant now = Instant.now();
        for (UUID player : queue.cleaning()) {
            Instant start = since.getOrDefault(player, now);
            boolean offline = proxy.getPlayer(player).isEmpty();
            boolean timedOut = Duration.between(start, now).compareTo(GIVE_UP) > 0;
            if (offline || timedOut || !held(player)) {
                if (timedOut && !offline) {
                    log.warn("세션 잠금이 {}초 넘게 남아 있습니다: {}", GIVE_UP.toSeconds(), player);
                }
                since.remove(player);
                queue.clearCleaning(player);
            }
        }
    }

    /** Whether a lock of a non-lobby server remains. */
    private boolean held(UUID player) {
        try {
            Optional<LockState> state = locks.inspect(player);
            return state.isPresent()
                    && !(state.get() instanceof LockState.Owned(String server)
                            && server.equals(lobby));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
