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
 * HikariCP 연결 풀 + 트랜잭션 도구.
 *
 * <ul>
 *   <li>{@link #tx}: 한 트랜잭션을 실행한다. 교착 상태(deadlock)면 최대 3번 다시 시도한다.
 *   <li>{@link #async}: 가상 스레드에서 실행하고 CompletableFuture를 돌려준다. 결과는 호출한 쪽이 메인 스레드로 넘겨 반영한다.
 * </ul>
 *
 * 메인 스레드에서 DB를 쓰면 개발 모드에서 경고 로그를 남긴다 (스택 포함).
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
     * 연결 풀을 연다. 연결에 실패하면 예외를 던진다 (서버 시작 중단).
     *
     * @param isMainThread 지금 스레드가 서버 메인 스레드인지 (Purpur: Bukkit::isPrimaryThread, 그 밖: () -> false)
     * @param warnOnMainThread 메인 스레드 입출력 감지 시 경고 (개발 빌드)
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

    /** 테스트·도구용: 이미 만든 DataSource로 연다. */
    public static Database wrap(HikariDataSource dataSource) {
        return new Database(dataSource, () -> false, false);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** 가상 스레드 실행기. DB와 관계없는 무거운 일에도 쓸 수 있다. */
    public ExecutorService executor() {
        return executor;
    }

    /** 한 트랜잭션을 실행하고 커밋한다. 예외가 나면 롤백한다. */
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
                    // 끊긴 연결에서 롤백이 실패해도 원래 예외(잔액 부족·중복·교착 판단의 근거)를 그대로 던진다
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
                        // 연결을 풀에 돌려줄 때 HikariCP가 정리한다
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

    /** 가상 스레드에서 트랜잭션을 실행한다. SQLException은 CompletionException으로 감싼다. */
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

    /** 풀 상태 (/harbor debug). */
    public PoolStats stats() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return new PoolStats(
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(),
                dataSource.getMaximumPoolSize());
    }

    /** 연결 풀 사용 현황. */
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
