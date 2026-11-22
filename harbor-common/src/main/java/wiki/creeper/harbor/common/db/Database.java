package wiki.creeper.harbor.common.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;

/**
 * HikariCP connection pool + transaction helpers.
 *
 * <ul>
 *   <li>{@link #tx}: runs one transaction. Retries up to 3 times on deadlock.
 *   <li>{@link #async}: runs on a virtual thread and returns a CompletableFuture. The caller hands
 *       the result back to the main thread to apply it.
 * </ul>
 *
 * Using the DB on the main thread logs a warning in dev mode (with stack trace).
 */
public final class Database implements AutoCloseable {

    public static final int MAX_DEADLOCK_RETRIES = 3;

    private static final System.Logger LOG = System.getLogger("Harbor/Database");
    private static final int MARIADB_DEADLOCK = 1213;

    private final HikariDataSource dataSource;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BooleanSupplier isMainThread;
    private final boolean warnOnMainThread;

    private Database(
            HikariDataSource dataSource, BooleanSupplier isMainThread, boolean warnOnMainThread) {
        this.dataSource = dataSource;
        this.isMainThread = isMainThread;
        this.warnOnMainThread = warnOnMainThread;
    }

    /**
     * Opens the connection pool. Throws if the connection fails (aborting server startup).
     *
     * @param isMainThread whether the current thread is the server main thread (Purpur:
     *     Bukkit::isPrimaryThread, otherwise: () -> false)
     * @param warnOnMainThread warn when main-thread I/O is detected (dev builds)
     */
    public static Database connect(
            DatabaseConfig config, BooleanSupplier isMainThread, boolean warnOnMainThread) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(config.poolName());
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(Math.min(2, config.poolSize()));
        hikari.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        hikari.setMaxLifetime(Duration.ofMinutes(25).toMillis());
        hikari.setRegisterMbeans(false);
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        return new Database(new HikariDataSource(hikari), isMainThread, warnOnMainThread);
    }

    /** For tests/tools: opens with an existing DataSource. */
    public static Database wrap(HikariDataSource dataSource) {
        return new Database(dataSource, () -> false, false);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Virtual thread executor. Can also be used for heavy work unrelated to the DB. */
    public ExecutorService executor() {
        return executor;
    }

    /** Runs and commits one transaction. Rolls back on exception. */
    public <T> T tx(SqlFunction<T> work) throws SQLException {
        checkThread();
        for (int attempt = 0; ; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    // Even if rollback fails on a broken connection, rethrow the original exception
                    // (the basis for insufficient-balance, duplicate and deadlock decisions)
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        e.addSuppressed(rollbackError);
                    }
                    throw e;
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // HikariCP cleans up when the connection is returned to the pool
                    }
                }
            } catch (SQLException e) {
                if (!isDeadlock(e) || attempt >= MAX_DEADLOCK_RETRIES) {
                    throw e;
                }
                LOG.log(Level.DEBUG, "교착 상태, 다시 시도 ({0}/{1})", attempt + 1, MAX_DEADLOCK_RETRIES);
                sleepQuietly(ThreadLocalRandom.current().nextLong(10, 50L * (attempt + 1)));
            }
        }
    }

    public void txVoid(SqlConsumer work) throws SQLException {
        tx(
                connection -> {
                    work.accept(connection);
                    return Boolean.TRUE;
                });
    }

    /** Runs a transaction on a virtual thread. SQLException is wrapped in CompletionException. */
    public <T> CompletableFuture<T> async(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return tx(work);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                executor);
    }

    public CompletableFuture<Void> asyncVoid(SqlConsumer work) {
        return async(
                        connection -> {
                            work.accept(connection);
                            return Boolean.TRUE;
                        })
                .thenApply(ignored -> null);
    }

    /** Pool status (/harbor debug). */
    public PoolStats stats() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return new PoolStats(
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(),
                dataSource.getMaximumPoolSize());
    }

    /** Connection pool usage. */
    public record PoolStats(int active, int idle, int total, int waiting, int max) {}

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, "DB 작업이 10초 안에 끝나지 않아 강제 종료합니다");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    static boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == MARIADB_DEADLOCK || "40001".equals(e.getSQLState());
    }

    private void checkThread() {
        if (warnOnMainThread && isMainThread.getAsBoolean()) {
            LOG.log(
                    Level.WARNING,
                    "메인 스레드에서 DB 입출력",
                    new IllegalStateException("main-thread DB access"));
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
