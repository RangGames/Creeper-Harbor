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
 * 지연 쓰기 큐. 도감·기록·퀘스트 진행처럼 몇 초 늦어도 되는 데이터를 모아서 쓴다.
 *
 * <ul>
 *   <li>5초마다, 또는 500건이 쌓이면 한 트랜잭션으로 쓴다.
 *   <li>같은 키는 마지막 값만 쓴다 (예: {@code codex:<uuid>:<fish>}).
 *   <li>한 건이 계속 실패해서 전체가 막히지 않도록, 묶음 쓰기가 실패하면 한 건씩 다시 쓴다. 연결 오류면 큐에 되돌리고, 데이터 오류면 로그를
 *       남기고 버린다.
 *   <li>{@link #close()}는 남은 것을 모두 쓴 뒤 끝난다.
 * </ul>
 *
 * 골드·물비늘·진주·경매·통발 수확은 이 큐를 쓰지 않고 바로 트랜잭션으로 처리한다.
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
        var unused = scheduler.scheduleWithFixedDelay(this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** 쓰기를 예약한다. 같은 키가 이미 있으면 새 값으로 바꾼다. */
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

    /** 큐 상태 (/harbor debug). */
    public record Stats(int pending, long written, long dropped) {}

    /** 지금 쌓인 것을 모두 쓴다. 연결 오류로 못 쓴 것은 큐에 남는다. */
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
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "묶음 쓰기 실패, 한 건씩 다시 씁니다 ({0}건): {1}", batch.size(), e.getMessage());
                flushOneByOne(batch);
            }
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * 키가 prefix로 시작하는 것만 지금 바로 한 트랜잭션으로 쓴다 (퇴장·서버 이동 때 그 플레이어 데이터만 즉시 저장). 실패하면 큐에 되돌리고
     * 예외를 던진다. 백그라운드 쓰기와 순서가 섞이지 않도록 같은 잠금을 쓴다.
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
            }
        }
    }

    /** 실패한 쓰기를 되돌린다. 그 사이 같은 키에 새 값이 들어왔으면 새 값을 유지한다. */
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

    /** 남은 것을 모두 쓰고 닫는다. 연결 오류가 계속되면 3번까지 다시 시도한다. */
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
