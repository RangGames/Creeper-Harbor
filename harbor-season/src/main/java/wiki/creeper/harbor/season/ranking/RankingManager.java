package wiki.creeper.harbor.season.ranking;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingBoard;
import wiki.creeper.harbor.season.api.RankingService;
import wiki.creeper.harbor.season.api.RankingSource;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.content.RankingBoardDef;
import wiki.creeper.harbor.season.content.RankingBoards;
import wiki.creeper.harbor.season.net.SeasonMessages;

/**
 * RankingService 구현 (docs 20 11.4 랭킹·리그).
 *
 * <ul>
 *   <li>집계: 서버마다 1분마다 harbor:lock:ranking을 시도하고, 얻은 서버만 집계 주기가 지난 보드(마지막 집계 시각은 모든 서버가 같은 캐시를 봄)를
 *       가상 스레드에서 다시 계산해 harbor:rank로 보낸다. 잠금은 풀지 않고 만료되게 두어 한 주기에 한 서버만 집계한다
 *   <li>스냅샷: 주간 초기화 때 ranking_snapshot에 주간 마감본, 시즌 전환 SNAPSHOT 단계에서 최종본(week = 0, 추가 제안)
 *   <li>원천 테이블이 아직 없으면 (그 모듈이 없으면) 그 보드는 비워 둔다
 * </ul>
 */
public final class RankingManager implements RankingService {

    static final String LOCK = "harbor:lock:ranking";

    /** ranking_snapshot.week의 시즌 최종본 값 (추가 제안). */
    public static final int FINAL_WEEK = 0;

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClock clock;
    private final Tasks tasks;
    private final Logger log;
    private final Map<String, RankingSource> sources = new ConcurrentHashMap<>();
    private final Map<String, RankingBoard> cache = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final List<Runnable> leaderTasks = new CopyOnWriteArrayList<>();

    public RankingManager(
            HarborApi api, SeasonContentView content, SeasonClock clock, Tasks tasks, Logger log) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.tasks = tasks;
        this.log = log;
        BuiltinSources.all().forEach(this::registerSource);
    }

    /** 집계 서버가 된 주기마다 함께 할 일 (명예의 전당 파생 기록 계산 등, 가상 스레드). */
    public void onLeaderTick(Runnable task) {
        leaderTasks.add(task);
    }

    public void start() {
        api.bus()
                .subscribe(
                        SeasonMessages.RANKING,
                        envelope -> {
                            SeasonMessages.RankingUpdate u = envelope.payload();
                            Optional<League> league = League.parse(u.league());
                            if (league.isPresent()) {
                                cache.put(
                                        key(u.board(), league.get()),
                                        new RankingBoard(
                                                u.board(),
                                                league.get(),
                                                u.season(),
                                                u.computedAt(),
                                                u.entries()));
                            }
                        });
        int period = content.balance().rankingCheckSeconds() * 20;
        var _ =
                tasks.timer(
                        period,
                        period,
                        () -> {
                            var _ = tasks.async(this::leaderTick);
                        });
        // 새로 켠 서버는 캐시가 비어 있으므로 직접 한 번 채운다 (발행하지 않음)
        var _ = tasks.async(() -> computeAll(false, false));
    }

    // ------------------------------------------------------------------ RankingService

    @Override
    public List<RankingBoardDef> boards() {
        return content.boards().boards();
    }

    @Override
    public Optional<RankingBoardDef> boardDef(String board) {
        return content.boards().get(board);
    }

    @Override
    public RankingBoard board(String board, League league) {
        RankingBoard b = cache.get(key(board, league));
        return b == null ? RankingBoard.empty(board, league, clock.current().value()) : b;
    }

    @Override
    public Optional<RankEntry> rankOf(UUID player, String board, League league) {
        return board(board, league).entries().stream()
                .filter(e -> player.equals(e.uuid()))
                .findFirst();
    }

    @Override
    public CompletableFuture<Boolean> refreshNow() {
        return tasks.supplyAsync(
                        () -> {
                            Optional<DistributedLock.Handle> lock = lock();
                            if (lock.isEmpty()) {
                                return false;
                            }
                            computeAll(true, true);
                            leaderTasks.forEach(this::runQuietly);
                            return true;
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public void registerSource(RankingSource source) {
        sources.put(source.id(), source);
    }

    // ------------------------------------------------------------------ 집계

    private void leaderTick() {
        if (lock().isEmpty()) {
            return;
        }
        computeAll(false, true);
        leaderTasks.forEach(this::runQuietly);
    }

    private void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("랭킹 집계 서버 작업 실패", e);
        }
    }

    private Optional<DistributedLock.Handle> lock() {
        try {
            return api.locks()
                    .tryAcquire(LOCK, Duration.ofSeconds(content.balance().rankingLockSeconds()));
        } catch (RuntimeException e) {
            log.warn("랭킹 잠금 실패, 이번 집계는 건너뜁니다: {}", String.valueOf(e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * @param force 주기와 관계없이 모두
     * @param publish harbor:rank로 보낼지 (false면 이 서버 캐시에만)
     */
    private void computeAll(boolean force, boolean publish) {
        int season = clock.current().value();
        Instant now = Instant.now();
        for (RankingBoardDef def : boards()) {
            for (League league : def.leagues()) {
                RankingBoard cached = cache.get(key(def.id(), league));
                boolean due =
                        force
                                || cached == null
                                || cached.season() != season
                                || Duration.between(cached.computedAt(), now).toMinutes()
                                        >= def.refreshMinutes();
                if (!due) {
                    continue;
                }
                Optional<RankingBoard> board = compute(def, league, season, null);
                if (board.isEmpty()) {
                    continue;
                }
                cache.put(key(def.id(), league), board.get());
                if (publish) {
                    api.bus()
                            .publish(
                                    SeasonMessages.RANKING,
                                    new SeasonMessages.RankingUpdate(
                                            def.id(),
                                            league.key(),
                                            season,
                                            board.get().computedAt(),
                                            board.get().entries()));
                }
            }
        }
    }

    /** 보드 하나를 집계한다 (블로킹). 원천이 없거나 테이블이 없으면 빈 Optional. */
    public Optional<RankingBoard> compute(
            RankingBoardDef def, League league, int season, @Nullable String schema) {
        RankingSource source = sources.get(def.source());
        if (source == null) {
            warnOnce(
                    "source:" + def.source(),
                    "랭킹 원천 '" + def.source() + "'이(가) 등록되지 않아 보드 " + def.id() + "를 건너뜁니다");
            return Optional.empty();
        }
        try {
            List<RankingSource.Row> rows =
                    api.database()
                            .async(c -> source.query(c, def, league, season, schema, def.size()))
                            .join();
            List<RankEntry> entries =
                    RankOrder.rank(
                            rows,
                            source.ascending(),
                            def.size(),
                            !def.source().equals(RankingBoards.BOSS_RECORDS));
            return Optional.of(new RankingBoard(def.id(), league, season, Instant.now(), entries));
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RejectedExecutionException) {
                // 서버 종료 중 DB 실행기가 닫혔다. 집계를 조용히 그만둔다
                return Optional.empty();
            }
            if (cause instanceof SQLSyntaxErrorException) {
                warnOnce(
                        "table:" + def.id(),
                        "랭킹 보드 "
                                + def.id()
                                + "의 원천 테이블을 읽지 못했습니다 (모듈 미설치?): "
                                + cause.getMessage());
            } else {
                log.error("랭킹 보드 {} 집계 실패", def.id(), cause);
            }
            return Optional.empty();
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.warn(message);
        }
    }

    // ------------------------------------------------------------------ 스냅샷

    /** 주간 마감 스냅샷 (주간 초기화 때, 한 서버만). */
    public void weeklySnapshot(int finishedWeek) {
        if (finishedWeek < 1) {
            return;
        }
        int season = clock.current().value();
        var _ =
                tasks.async(
                        () -> {
                            Optional<DistributedLock.Handle> lock;
                            try {
                                lock =
                                        api.locks()
                                                .tryAcquire(
                                                        LOCK
                                                                + ":snapshot:"
                                                                + season
                                                                + ":"
                                                                + finishedWeek,
                                                        Duration.ofHours(6));
                            } catch (RuntimeException e) {
                                log.warn("주간 랭킹 스냅샷 잠금 실패: {}", String.valueOf(e.getMessage()));
                                return;
                            }
                            if (lock.isEmpty()) {
                                return;
                            }
                            try {
                                int rows =
                                        api.database()
                                                .async(c -> snapshot(c, season, finishedWeek, null))
                                                .join();
                                log.info("주간 랭킹 스냅샷: 시즌 {} {}주차 {}행", season, finishedWeek, rows);
                            } catch (RuntimeException e) {
                                log.error("주간 랭킹 스냅샷 실패", e);
                            }
                        });
    }

    /**
     * 모든 보드를 다시 집계해 ranking_snapshot에 넣는다 (INSERT IGNORE라 다시 실행해도 같다). 블로킹.
     *
     * @param schema 원천을 읽을 스키마 (시즌 최종본은 archive_s&lt;n&gt;)
     * @return 넣은 행 수
     */
    public int snapshot(Connection c, int season, int week, @Nullable String schema)
            throws SQLException {
        int total = 0;
        Instant now = Instant.now();
        for (RankingBoardDef def : boards()) {
            RankingSource source = sources.get(def.source());
            if (source == null) {
                continue;
            }
            for (League league : def.leagues()) {
                List<RankingSource.Row> rows;
                try {
                    rows = source.query(c, def, league, season, schema, def.size());
                } catch (SQLSyntaxErrorException e) {
                    continue;
                }
                List<RankEntry> entries =
                        RankOrder.rank(
                                rows,
                                source.ascending(),
                                def.size(),
                                !def.source().equals(RankingBoards.BOSS_RECORDS));
                total += insertSnapshot(c, season, week, def.id(), league, entries, now);
            }
        }
        return total;
    }

    static int insertSnapshot(
            Connection c,
            int season,
            int week,
            String board,
            League league,
            List<RankEntry> entries,
            Instant takenAt)
            throws SQLException {
        if (entries.isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO ranking_snapshot (season, week, board, league, `rank`, uuid, name, value, taken_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (RankEntry e : entries) {
                UUID uuid = e.uuid();
                ps.setInt(1, season);
                ps.setInt(2, week);
                ps.setString(3, board);
                ps.setString(4, league.key());
                ps.setInt(5, e.rank());
                ps.setString(6, uuid == null ? null : uuid.toString());
                ps.setString(7, e.name().length() > 64 ? e.name().substring(0, 64) : e.name());
                ps.setDouble(8, e.value());
                ps.setTimestamp(9, Timestamp.from(takenAt));
                ps.addBatch();
            }
            int n = 0;
            for (int r : ps.executeBatch()) {
                n += Math.max(0, r);
            }
            return n;
        }
    }

    /** 지난 주차·시즌 스냅샷 읽기 (GUI). week = 0이면 시즌 최종본. */
    public CompletableFuture<List<RankEntry>> snapshotEntries(
            int season, int week, String board, League league) {
        return api.database()
                .async(
                        c -> {
                            List<RankEntry> result = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT `rank`, uuid, name, value, taken_at FROM ranking_snapshot"
                                                    + " WHERE season = ? AND week = ? AND board = ? AND league = ? ORDER BY `rank`")) {
                                ps.setInt(1, season);
                                ps.setInt(2, week);
                                ps.setString(3, board);
                                ps.setString(4, league.key());
                                try (var rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        String raw = rs.getString(2);
                                        result.add(
                                                new RankEntry(
                                                        rs.getInt(1),
                                                        raw == null ? null : UUID.fromString(raw),
                                                        rs.getString(3),
                                                        rs.getDouble(4),
                                                        0,
                                                        rs.getTimestamp(5).toInstant(),
                                                        1));
                                    }
                                }
                            }
                            return List.copyOf(result);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    private static String key(String board, League league) {
        return board + "|" + league.key();
    }
}
