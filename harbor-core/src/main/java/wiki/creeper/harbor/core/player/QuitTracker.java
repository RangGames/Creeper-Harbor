package wiki.creeper.harbor.core.player;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Tracks connections and in-flight quits on this server (split out of {@link PlayerManager}) so a
 * rejoin to the same server waits for the previous session's quit save and lock release, and quit
 * saves that failed are retried while their lock is kept alive.
 */
final class QuitTracker {

    private static final long PREVIOUS_SESSION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final InfraConfig infra;
    private final WriteBehindQueue queue;
    private final SessionLocks locks;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;

    /** Players connected to this server (also read from the pre-login event thread). */
    private final Set<UUID> onlineIds = ConcurrentHashMap.newKeySet();

    /** Quit handling in progress (save and lock release). A rejoin to this server waits for it. */
    private final Map<UUID, CompletableFuture<Void>> quitting = new ConcurrentHashMap<>();

    /** Players whose quit save failed and is still queued. Their lock is extended until saved. */
    private final Set<UUID> unsavedQuits = ConcurrentHashMap.newKeySet();

    QuitTracker(
            InfraConfig infra,
            WriteBehindQueue queue,
            SessionLocks locks,
            MessageService messages,
            Tasks tasks,
            Logger log) {
        this.infra = infra;
        this.queue = queue;
        this.locks = locks;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
    }

    /**
     * Pre-login (a thread that may block): waits until the previous connection on this server is
     * cleaned up. If the previous connection is still here it is kicked (Paper only drops the
     * duplicate connection after the pre-login stage, so just waiting would never finish), then
     * waits for its quit save and lock release. If its quit save failed and is still queued, it is
     * written now. Returns false (deny the login) if this doesn't finish within the time limit.
     */
    boolean awaitPreviousSession(UUID uuid) {
        long deadline = System.nanoTime() + PREVIOUS_SESSION_WAIT_NANOS;
        if (onlineIds.contains(uuid)) {
            tasks.main()
                    .execute(
                            () -> {
                                Player old = Bukkit.getPlayer(uuid);
                                if (old != null) {
                                    old.kick(messages.get("session.moved"));
                                }
                            });
        }
        try {
            while (onlineIds.contains(uuid)) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                Thread.sleep(50);
            }
            CompletableFuture<Void> quit = quitting.get(uuid);
            if (quit != null) {
                quit.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
        if (unsavedQuits.contains(uuid)) {
            try {
                queue.flushNow(PlayerManager.prefix(uuid));
                unsavedQuits.remove(uuid);
            } catch (SQLException e) {
                log.warn("이전 접속 데이터를 아직 저장하지 못해 접속을 미룹니다: {}", uuid);
                return false;
            }
        }
        return true;
    }

    /** Main thread: the player joined this server. */
    void joined(UUID uuid) {
        onlineIds.add(uuid);
    }

    /** Main thread: the player quit without loaded data, so there is no quit work to track. */
    void leftUntracked(UUID uuid) {
        onlineIds.remove(uuid);
    }

    /**
     * Records the quit handling (save and lock release). Called inside the quit event, then clears
     * the connected mark (a rejoin waits in this order).
     */
    void trackQuit(UUID uuid, CompletableFuture<Void> work) {
        quitting.put(uuid, work);
        onlineIds.remove(uuid);
        var _ = work.whenComplete((v, e) -> quitting.remove(uuid, work));
    }

    /** The quit save failed: keep extending the lock until it is written. */
    void markUnsaved(UUID uuid) {
        unsavedQuits.add(uuid);
    }

    /** Live view of players whose quit save failed (their locks are still extended). */
    Set<UUID> unsavedQuits() {
        return unsavedQuits;
    }

    /**
     * Players whose quit save failed: try writing again and stop extending the lock once it
     * succeeds (refreshLocks extends the lock meanwhile). The lock is not released directly: the
     * player may have rejoined this server in between and taken a lock with the same value
     * ("owned:this server"), which would be removed. It expires within 30 seconds.
     */
    void retryUnsavedQuits() {
        List<UUID> targets = List.copyOf(unsavedQuits);
        var _ =
                tasks.async(
                        () -> {
                            for (UUID uuid : targets) {
                                if (quitting.containsKey(uuid) || onlineIds.contains(uuid)) {
                                    continue;
                                }
                                try {
                                    queue.flushNow(PlayerManager.prefix(uuid));
                                    if (unsavedQuits.remove(uuid)) {
                                        log.info("퇴장 저장을 마쳤습니다: {}", uuid);
                                    }
                                } catch (SQLException e) {
                                    log.warn("퇴장 저장 재시도 실패: {}", uuid);
                                }
                            }
                        });
    }

    /**
     * Server shutdown: one last attempt for players whose earlier quit save failed (on failure
     * other servers wait until the lock expires).
     */
    void flushUnsavedOnShutdown() {
        for (UUID uuid : List.copyOf(unsavedQuits)) {
            try {
                queue.flushNow(PlayerManager.prefix(uuid));
                unsavedQuits.remove(uuid);
                locks.release(uuid, infra.serverId());
            } catch (SQLException e) {
                log.error("종료 저장 실패 (이전 퇴장): {}", uuid, e);
            }
        }
    }
}
