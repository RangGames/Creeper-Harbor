package wiki.creeper.harbor.common.db;

import java.lang.System.Logger.Level;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Write-behind queue. Batches data that can be a few seconds late, like codex, records and quest
 * progress.
 *
 * <ul>
 *   <li>Writes in one transaction every 5 seconds or once 500 entries pile up.
 *   <li>Only the last value per key is written (e.g. {@code codex:<uuid>:<fish>}).
 *   <li>So one constantly failing entry cannot block everything, a failed batch write is retried
 *       one entry at a time. Connection errors go back to the queue; data errors are logged and
 *       dropped.
 *   <li>{@link #close()} returns after writing everything left.
 * </ul>
 *
 * Gold, mulbineul, pearls, auctions and trap harvests do not use this queue; they are handled in
 * immediate transactions.
 */
public final class WriteBehindQueue implements AutoCloseable {

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_BATCH_SIZE = 500;

    private static final System.Logger LOG = System.getLogger("Harbor/WriteBehind");

    private final Database database;
    private final int batchSize;
    private final Object pendingLock = new Object();
    private final ReentrantLock flushLock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private LinkedHashMap<String, SqlConsumer> pending = new LinkedHashMap<>();
    private volatile boolean closed;

    public WriteBehindQueue(Database database) {
        this(database, DEFAULT_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    public WriteBehindQueue(Database database, Duration interval, int batchSize) {
        this.database = database;
        this.batchSize = batchSize;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("harbor-write-behind").daemon(true).factory());
        long millis = interval.toMillis();
        var _ =
                scheduler.scheduleWithFixedDelay(
                        this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Schedules a write. If the key is already queued, replaces it with the new value. */
    public void enqueue(String key, SqlConsumer write) {
        if (closed) {
            throw new IllegalStateException("지연 쓰기 큐가 이미 닫혔습니다");
        }
        int size;
        synchronized (pendingLock) {
            pending.remove(key);
            pending.put(key, write);
            size = pending.size();
        }
        if (size >= batchSize) {
            scheduler.execute(this::flushQuietly);
        }
    }

    public int pendingCount() {
        synchronized (pendingLock) {
            return pending.size();
        }
    }

    public Stats stats() {
        return new Stats(pendingCount(), written.get(), dropped.get());
    }

    /** Queue status (/harbor debug). */
    public record Stats(int pending, long written, long dropped) {}

    /**
     * Writes everything queued now. Entries that fail due to connection errors stay in the queue.
     */
    public void flush() {
        flushLock.lock();
        try {
            Map<String, SqlConsumer> batch;
            synchronized (pendingLock) {
                if (pending.isEmpty()) {
                    return;
                }
                batch = pending;
                pending = new LinkedHashMap<>();
            }
            try {
                database.txVoid(
                        connection -> {
                            for (SqlConsumer write : batch.values()) {
                                write.accept(connection);
                            }
                        });
                written.addAndGet(batch.size());
            } catch (SQLException | RuntimeException e) {
                // A runtime exception from one entry (serialization error, etc.) also rolls back
                // the whole batch. Previously only SQLException was caught, so a batch already
                // taken from the queue
                // (including other players' saves) was lost entirely
                LOG.log(
                        Level.WARNING,
                        "묶음 쓰기 실패, 한 건씩 다시 씁니다 ({0}건): {1}",
                        batch.size(),
                        String.valueOf(e.getMessage()));
                flushOneByOne(batch);
            }
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Immediately writes, in one transaction, only the keys starting with prefix (saves just that
     * player's data on quit/server move). On failure, puts them back and throws. Uses the same lock
     * so its ordering does not interleave with background writes.
     */
    public void flushNow(String keyPrefix) throws SQLException {
        flushLock.lock();
        try {
            Map<String, SqlConsumer> batch = new LinkedHashMap<>();
            synchronized (pendingLock) {
                var it = pending.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, SqlConsumer> entry = it.next();
                    if (entry.getKey().startsWith(keyPrefix)) {
                        batch.put(entry.getKey(), entry.getValue());
                        it.remove();
                    }
                }
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                database.txVoid(
                        connection -> {
                            for (SqlConsumer write : batch.values()) {
                                write.accept(connection);
                            }
                        });
                written.addAndGet(batch.size());
            } catch (SQLException e) {
                batch.forEach(this::requeue);
                throw e;
            } catch (RuntimeException e) {
                // Data error in one entry: putting it back would make this player's save fail
                // forever, so write one at a time and drop only that entry.
                // If anything went back into the queue due to connection problems, report it as not
                // saved
                LOG.log(Level.WARNING, "즉시 쓰기 실패, 한 건씩 다시 씁니다: " + keyPrefix, e);
                flushOneByOne(batch);
                synchronized (pendingLock) {
                    for (String key : batch.keySet()) {
                        if (pending.containsKey(key)) {
                            throw new SQLException("즉시 쓰기 일부 실패: " + key, e);
                        }
                    }
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    private void flushOneByOne(Map<String, SqlConsumer> batch) {
        boolean connectionDown = false;
        for (Map.Entry<String, SqlConsumer> entry : batch.entrySet()) {
            if (connectionDown) {
                requeue(entry.getKey(), entry.getValue());
                continue;
            }
            try {
                database.txVoid(entry.getValue());
                written.incrementAndGet();
            } catch (SQLException e) {
                if (isConnectionProblem(e)) {
                    connectionDown = true;
                    requeue(entry.getKey(), entry.getValue());
                } else {
                    dropped.incrementAndGet();
                    LOG.log(Level.ERROR, "지연 쓰기 실패로 버립니다: " + entry.getKey(), e);
                }
            } catch (RuntimeException e) {
                // Data error in that entry: retrying gives the same result, so drop only that entry
                // and keep writing the rest
                dropped.incrementAndGet();
                LOG.log(Level.ERROR, "지연 쓰기 오류로 버립니다: " + entry.getKey(), e);
            }
        }
    }

    /**
     * Restores failed writes. If a newer value arrived for the same key meanwhile, the newer value
     * is kept.
     */
    private void requeue(String key, SqlConsumer write) {
        synchronized (pendingLock) {
            pending.putIfAbsent(key, write);
        }
    }

    private static boolean isConnectionProblem(SQLException e) {
        String state = e.getSQLState();
        return e instanceof SQLTransientException
                || e instanceof SQLNonTransientConnectionException
                || (state != null && state.startsWith("08"));
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "지연 쓰기 중 오류", e);
        }
    }

    /** Writes everything left and closes. Retries up to 3 times if connection errors persist. */
    @Override
    public void close() {
        closed = true;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int attempt = 0; attempt < 3 && pendingCount() > 0; attempt++) {
            flush();
        }
        int left = pendingCount();
        if (left > 0) {
            LOG.log(Level.ERROR, "종료 시 지연 쓰기 {0}건을 쓰지 못했습니다", left);
        }
    }
}
