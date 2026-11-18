package wiki.creeper.harbor.season.transition;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.ledger.LedgerAuditor;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.data.SeasonMeta;
import wiki.creeper.harbor.season.data.SeasonRepository;
import wiki.creeper.harbor.season.data.TitleStore;

/**
 * 시즌 전환 엔진 (docs 20 11.4 TransitionEngine). Paper API를 쓰지 않는 순수 Java라 본 서버 플러그인과 harbor-tools 리허설
 * CLI가 같은 코드를 쓸 수 있다. 바깥 일(공지·FREEZE·원장 정리·랭킹 확정)은 {@link TransitionEnvironment}에 맡긴다.
 *
 * <ul>
 *   <li>단계마다 season_transition_log에 시작·완료를 남기고, 모든 단계는 다시 실행해도 결과가 같다 (아카이브는 행 수·CRC32가 같으면 건너뜀,
 *       삭제는 조건 삭제, 원장은 참조 ID 유니크, 칭호는 INSERT IGNORE)
 *   <li>harbor:lock:season-transition 분산 잠금과 season_meta 조건부 갱신으로 두 서버가 동시에 전환하지 못한다
 *   <li>서버가 죽으면 {@link #resume}이 일지에서 마지막으로 끝난 단계 다음부터 잇는다
 *   <li>롤백 A(RESET 전): {@link #rollback}. 롤백 B·C(RESET 뒤)는 5단계 백업 복원으로 운영자가 한다
 * </ul>
 */
public final class TransitionEngine {

    private static final System.Logger LOG = System.getLogger("Harbor/SeasonTransition");

    static final String LOCK = "harbor:lock:season-transition";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    /** 진행 상태. */
    public enum Status {
        IDLE,
        RUNNING,
        WAITING_BACKUP,
        FAILED,
        FINISHED
    }

    /** 지금 실행 상태 (명령어 표시용). */
    public record View(
            @Nullable String transitionId,
            int fromSeason,
            int toSeason,
            @Nullable TransitionStep step,
            Status status,
            @Nullable String error,
            String operator) {}

    private final Database db;
    private final DistributedLock locks;
    private final TransitionEnvironment env;
    private final Supplier<ResetPlan> plans;
    private final TransitionLog journal;
    private volatile @Nullable Run run;

    private static final class Run {
        final String id;
        final int from;
        final int to;
        final String operator;
        final ResetPlan plan;
        final CompletableFuture<String> backupConfirmed = new CompletableFuture<>();
        volatile @Nullable TransitionStep step;
        volatile Status status = Status.RUNNING;
        volatile @Nullable String error;
        volatile boolean cancelled;

        Run(String id, int from, int to, String operator, ResetPlan plan) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.operator = operator;
            this.plan = plan;
        }
    }

    public TransitionEngine(
            Database db,
            DistributedLock locks,
            TransitionEnvironment env,
            Supplier<ResetPlan> plans) {
        this.db = db;
        this.locks = locks;
        this.env = env;
        this.plans = plans;
        this.journal = new TransitionLog(db);
    }

    // ------------------------------------------------------------------ 명령

    public View view() {
        Run r = run;
        if (r == null) {
            int season = env.currentSeason();
            return new View(null, season, season + 1, null, Status.IDLE, null, "");
        }
        return new View(r.id, r.from, r.to, r.step, r.status, r.error, r.operator);
    }

    /** 가장 최근 전환의 일지 (블로킹). */
    public List<LogRow> history() throws SQLException {
        Run r = run;
        Optional<String> id = r != null ? Optional.of(r.id) : journal.latest(env.currentSeason());
        return id.isPresent() ? journal.rows(id.get()) : List.of();
    }

    /**
     * 새 전환을 시작한다 (블로킹, 시작만 하고 돌아온다).
     *
     * @return 거부 사유 (시작했으면 빈 Optional)
     */
    public synchronized Optional<String> start(String operator) throws SQLException {
        if (isActive()) {
            return Optional.of("이미 전환이 진행 중입니다");
        }
        int from = env.currentSeason();
        Optional<String> latest = journal.latest(from);
        if (latest.isPresent()) {
            List<LogRow> rows = journal.rows(latest.get());
            if (TransitionProgress.next(rows).isPresent() && !rolledBack(rows)) {
                return Optional.of("끝나지 않은 전환 " + latest.get() + "이(가) 있습니다. resume으로 이어서 하세요");
            }
            if (TransitionProgress.next(rows).isEmpty()) {
                return Optional.of("시즌 " + from + " 전환은 이미 끝났습니다. 서버의 season.id를 바꾸고 재시작하세요");
            }
        }
        Run r = new Run(UUID.randomUUID().toString(), from, from + 1, operator, plans.get());
        launch(r, TransitionStep.PRECHECK);
        return Optional.empty();
    }

    /** 일지의 마지막 완료 단계 다음부터 잇는다 (블로킹, 시작만 하고 돌아온다). */
    public synchronized Optional<String> resume(String operator) throws SQLException {
        if (isActive()) {
            return Optional.of("이미 전환이 진행 중입니다");
        }
        int from = env.currentSeason();
        Optional<String> latest = journal.latest(from);
        if (latest.isEmpty()) {
            return Optional.of("이어서 할 전환이 없습니다");
        }
        List<LogRow> rows = journal.rows(latest.get());
        if (rolledBack(rows)) {
            return Optional.of("롤백한 전환입니다. start로 새로 시작하세요");
        }
        Optional<TransitionStep> next = TransitionProgress.next(rows);
        if (next.isEmpty()) {
            return Optional.of("이미 끝난 전환입니다");
        }
        Run r = new Run(latest.get(), from, from + 1, operator, plans.get());
        launch(r, next.get());
        return Optional.empty();
    }

    /**
     * 백업 확인 (5단계). 콘솔이거나, 전환을 시작한 관리자가 아닌 다른 관리자여야 한다 (docs 20 11.5: 두 명 중 한 명의 확인).
     *
     * @return 거부 사유 (받아들였으면 빈 Optional)
     */
    public Optional<String> confirmBackup(String confirmer, boolean console) {
        Run r = run;
        if (r == null || r.status != Status.WAITING_BACKUP) {
            return Optional.of("백업 확인을 기다리는 전환이 없습니다");
        }
        if (!console && confirmer.equals(r.operator)) {
            return Optional.of("전환을 시작한 관리자가 아닌 다른 관리자 또는 콘솔이 확인해야 합니다");
        }
        try {
            Instant frozen = freezeTime(journal.rows(r.id));
            Optional<String> marker = checkMarker(r.plan, frozen);
            if (marker.isPresent()) {
                return marker;
            }
        } catch (SQLException e) {
            return Optional.of("일지를 읽지 못했습니다: " + e.getMessage());
        }
        r.backupConfirmed.complete(confirmer);
        return Optional.empty();
    }

    /**
     * 롤백 A (RESET 전 실패, docs 20 11.4): 아카이브 스키마를 지우고 season_meta를 ACTIVE로 되돌린 뒤 접속 차단을 푼다. 블로킹.
     *
     * @return 거부 사유 (되돌렸으면 빈 Optional)
     */
    public synchronized Optional<String> rollback(String operator) throws SQLException {
        Run r = run;
        if (r != null && r.status == Status.RUNNING) {
            return Optional.of("진행 중인 단계가 끝나거나 실패한 뒤에 롤백할 수 있습니다");
        }
        int from = env.currentSeason();
        Optional<String> latest = journal.latest(from);
        if (latest.isEmpty()) {
            return Optional.of("되돌릴 전환이 없습니다");
        }
        List<LogRow> rows = journal.rows(latest.get());
        if (TransitionProgress.pastRollbackA(rows)) {
            return Optional.of(
                    "RESET 단계가 시작되어 롤백 A를 할 수 없습니다. 5단계 백업으로 DB와 playerdata를 복원하세요 (롤백 B)");
        }
        if (r != null) {
            r.cancelled = true;
            r.backupConfirmed.completeExceptionally(new CancellationException("롤백"));
        }
        String id = latest.get();
        journal.begin(id, from, from + 1, "ROLLBACK_A", operator);
        db.txVoid(
                c -> {
                    try (PreparedStatement ps = c.prepareStatement(ArchiveSql.dropSchema(from))) {
                        ps.executeUpdate();
                    }
                    SeasonRepository.transition(
                            c, from, SeasonPhase.TRANSITIONING, SeasonPhase.ACTIVE);
                });
        journal.finish(id, "ROLLBACK_A", LogRow.DONE, 0, 0, "아카이브 삭제, ACTIVE 복귀");
        env.metaChanged(from);
        env.thaw(id, from);
        run = null;
        LOG.log(Level.WARNING, "시즌 {0} 전환 롤백 A 완료 ({1})", from, operator);
        return Optional.empty();
    }

    private boolean isActive() {
        Run r = run;
        return r != null && (r.status == Status.RUNNING || r.status == Status.WAITING_BACKUP);
    }

    private static boolean rolledBack(List<LogRow> rows) {
        return TransitionProgress.isDone(rows, "ROLLBACK_A");
    }

    private void launch(Run r, TransitionStep first) {
        run = r;
        r.step = first;
        Thread.ofVirtual().name("harbor-season-transition").start(() -> execute(r, first));
    }

    // ------------------------------------------------------------------ 실행

    private void execute(Run r, TransitionStep first) {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = locks.tryAcquire(LOCK, LOCK_TTL);
        } catch (RuntimeException e) {
            fail(r, "전환 잠금을 얻지 못했습니다 (Redis): " + e.getMessage());
            return;
        }
        if (lock.isEmpty()) {
            fail(r, "다른 서버가 전환을 실행 중입니다");
            return;
        }
        DistributedLock.Handle handle = lock.get();
        Thread keepAlive =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (!Thread.currentThread().isInterrupted()) {
                                        try {
                                            Thread.sleep(Duration.ofMinutes(1));
                                        } catch (InterruptedException e) {
                                            return;
                                        }
                                        try {
                                            handle.extend(LOCK_TTL);
                                        } catch (RuntimeException e) {
                                            LOG.log(
                                                    Level.WARNING,
                                                    "전환 잠금 연장 실패: " + e.getMessage());
                                        }
                                    }
                                });
        try {
            Optional<TransitionStep> step = Optional.of(first);
            while (step.isPresent()) {
                if (r.cancelled) {
                    throw new TransitionException("취소되었습니다");
                }
                TransitionStep s = step.get();
                r.step = s;
                journal.begin(r.id, r.from, r.to, s.name(), r.operator);
                env.progress("season.transition.step_start", Map.of("step", s.name()));
                Result result = runStep(r, s);
                journal.finish(
                        r.id,
                        s.name(),
                        LogRow.DONE,
                        result.rows(),
                        result.checksum(),
                        result.detail());
                env.progress(
                        "season.transition.step_done",
                        Map.of(
                                "step",
                                s.name(),
                                "detail",
                                result.detail() == null ? "" : result.detail()));
                step = s.next();
            }
            r.status = Status.FINISHED;
            env.progress(
                    "season.transition.finished",
                    Map.of("from", String.valueOf(r.from), "to", String.valueOf(r.to)));
            LOG.log(Level.INFO, "시즌 {0} → {1} 전환 완료", r.from, r.to);
        } catch (TransitionException e) {
            failStep(r, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failStep(r, "중단되었습니다");
        } catch (Exception e) {
            LOG.log(Level.ERROR, "시즌 전환 오류", e);
            failStep(r, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            keepAlive.interrupt();
            try {
                handle.release();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "전환 잠금 해제 실패: " + e.getMessage());
            }
        }
    }

    private void failStep(Run r, String message) {
        TransitionStep s = r.step;
        if (s != null) {
            try {
                journal.finish(r.id, s.name(), LogRow.FAILED, 0, 0, message);
            } catch (SQLException e) {
                LOG.log(Level.ERROR, "전환 일지 기록 실패", e);
            }
        }
        fail(r, message);
    }

    private void fail(Run r, String message) {
        r.status = Status.FAILED;
        r.error = message;
        TransitionStep s = r.step;
        env.progress(
                "season.transition.failed",
                Map.of("step", s == null ? "-" : s.name(), "error", message));
        LOG.log(Level.ERROR, "시즌 전환 실패 ({0}): {1}", s, message);
    }

    /** 단계 결과 (일지 rows·checksum·detail). */
    private record Result(long rows, long checksum, @Nullable String detail) {
        static Result of(long rows, @Nullable String detail) {
            return new Result(rows, 0, detail);
        }
    }

    private Result runStep(Run r, TransitionStep step) throws Exception {
        return switch (step) {
            case PRECHECK -> precheckStep(r);
            case NOTICE -> notice(r);
            case FREEZE -> freeze(r);
            case VERIFY -> verify(r);
            case BACKUP -> backup(r);
            case ARCHIVE -> archive(r);
            case SNAPSHOT -> snapshot(r);
            case RESET -> reset(r);
            case CONTENT -> content(r);
            case OPEN -> open(r);
        };
    }

    // ------------------------------------------------------------------ 1. PRECHECK

    private Result precheckStep(Run r) throws Exception {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long size = precheck(r.plan, r.from, r.to, errors, warnings);
        warnings.forEach(w -> LOG.log(Level.WARNING, "PRECHECK 경고: " + w));
        if (!errors.isEmpty()) {
            throw new TransitionException(
                    "PRECHECK 실패 " + errors.size() + "건: " + String.join(" / ", errors));
        }
        return Result.of(size, "경고 " + warnings.size() + "건");
    }

    /**
     * 사전 검사 (docs 20 11.4 1단계). 오류는 errors, 경고는 warnings에 넣는다.
     *
     * @return 지금 DB 크기 (바이트)
     */
    long precheck(ResetPlan plan, int from, int to, List<String> errors, List<String> warnings)
            throws SQLException {
        if (env.currentSeason() != from) {
            errors.add("이 서버의 시즌(" + env.currentSeason() + ")이 전환할 시즌(" + from + ")과 다릅니다");
        }
        if (plan.tables().isEmpty()) {
            errors.add("season/reset_plan.yml에 테이블 계획이 없습니다");
        }
        long size =
                db.tx(
                        c -> {
                            Optional<SeasonMeta> meta = SeasonRepository.meta(c, from);
                            if (meta.isEmpty()) {
                                errors.add("season_meta에 시즌 " + from + " 행이 없습니다");
                            } else if (meta.get().state() != SeasonPhase.ACTIVE
                                    && meta.get().state() != SeasonPhase.TRANSITIONING) {
                                errors.add(
                                        "시즌 "
                                                + from
                                                + " 상태가 "
                                                + meta.get().state()
                                                + "입니다 (ACTIVE여야 함)");
                            }
                            Set<String> seasonTables = SchemaInspector.seasonTables(c);
                            for (String t : PlanCoverage.missing(plan, seasonTables)) {
                                errors.add("season 열이 있는 테이블 " + t + "이(가) reset_plan.yml에 없습니다");
                            }
                            for (ResetPlan.TableRule rule : plan.tables()) {
                                if (!SchemaInspector.tableExists(c, null, rule.table())) {
                                    warnings.add("테이블 " + rule.table() + "이(가) 없어 건너뜁니다 (모듈 미설치)");
                                    continue;
                                }
                                if (rule.scope() == ResetPlan.Scope.SEASON_COLUMN
                                        && !SchemaInspector.columns(c, rule.table())
                                                .contains(rule.column())) {
                                    errors.add(
                                            "테이블 "
                                                    + rule.table()
                                                    + "에 열 "
                                                    + rule.column()
                                                    + "이(가) 없습니다 (scope: full?)");
                                    continue;
                                }
                                if (rule.keepWhere() != null) {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(ArchiveSql.explainKeep(rule))) {
                                        if (ArchiveSql.hasSeasonParam(rule)) {
                                            ps.setInt(1, from);
                                        }
                                        ps.executeQuery().close();
                                    } catch (SQLException e) {
                                        errors.add(
                                                "테이블 "
                                                        + rule.table()
                                                        + " keep_where 오류: "
                                                        + e.getMessage());
                                    }
                                }
                            }
                            if (!plan.fishLedgerStates().isEmpty()
                                    && !SchemaInspector.tableExists(c, null, "fish_ledger")) {
                                warnings.add("fish_ledger 테이블이 없습니다");
                            }
                            return SchemaInspector.databaseBytes(c);
                        });
        OptionalLong free = env.freeDiskBytes();
        long need = (long) (size * plan.transition().diskFactor());
        if (free.isEmpty()) {
            warnings.add(
                    "DB 서버 디스크 여유를 확인할 수 없습니다. 직접 확인하세요 (필요 "
                            + (need >> 20)
                            + "MB = DB 크기 × "
                            + plan.transition().diskFactor()
                            + ")");
        } else if (free.getAsLong() < need) {
            errors.add("디스크 여유 " + (free.getAsLong() >> 20) + "MB < 필요 " + (need >> 20) + "MB");
        }
        for (ResetPlan.OpenTitle t : plan.openTitles()) {
            if (!env.titleExists(t.title())) {
                errors.add("open_titles의 칭호 " + t.title() + "이(가) titles.yml에 없습니다");
            }
        }
        errors.addAll(env.checkContent(to));
        errors.addAll(env.checkResourcePack(to));
        return size;
    }

    // ------------------------------------------------------------------ 2. NOTICE

    private Result notice(Run r) throws Exception {
        List<Integer> minutes = r.plan.transition().noticeMinutes();
        if (minutes.isEmpty()) {
            return Result.of(0, null);
        }
        Instant started =
                journal.rows(r.id).stream()
                        .filter(row -> row.step().equals(TransitionStep.NOTICE.name()))
                        .map(LogRow::startedAt)
                        .findFirst()
                        .orElse(Instant.now());
        Instant deadline = started.plus(Duration.ofMinutes(minutes.getFirst()));
        int sent = 0;
        for (int m : minutes) {
            Instant at = deadline.minus(Duration.ofMinutes(m));
            if (Instant.now().isAfter(at.plusSeconds(30))) {
                continue;
            }
            sleepUntil(r, at);
            env.announce("season.transition.countdown", Map.of("minutes", String.valueOf(m)));
            sent++;
        }
        sleepUntil(r, deadline);
        return Result.of(sent, "공지 " + sent + "회");
    }

    private static void sleepUntil(Run r, Instant at)
            throws TransitionException, InterruptedException {
        while (Instant.now().isBefore(at)) {
            if (r.cancelled) {
                throw new TransitionException("취소되었습니다");
            }
            long ms = Math.min(1000, Duration.between(Instant.now(), at).toMillis());
            if (ms > 0) {
                Thread.sleep(ms);
            }
        }
    }

    // ------------------------------------------------------------------ 3. FREEZE

    private Result freeze(Run r) throws Exception {
        boolean changed =
                db.tx(
                        c -> {
                            if (SeasonRepository.transition(
                                    c, r.from, SeasonPhase.ACTIVE, SeasonPhase.TRANSITIONING)) {
                                return true;
                            }
                            Optional<SeasonMeta> meta = SeasonRepository.meta(c, r.from);
                            if (meta.isPresent()
                                    && meta.get().state() == SeasonPhase.TRANSITIONING) {
                                return false;
                            }
                            throw new SQLException("season_meta 상태가 ACTIVE가 아닙니다");
                        });
        env.metaChanged(r.from);
        TransitionEnvironment.FreezeResult result =
                env.freeze(
                        r.id,
                        r.from,
                        Duration.ofSeconds(r.plan.transition().flushTimeoutSeconds()));
        if (!result.ok()) {
            throw new TransitionException("응답하지 않은 서버: " + String.join(", ", result.missing()));
        }
        return Result.of(
                result.servers().size(),
                (changed ? "TRANSITIONING, " : "") + "서버 " + String.join(", ", result.servers()));
    }

    // ------------------------------------------------------------------ 4. VERIFY

    private Result verify(Run r) throws Exception {
        ReadOnlySeasonBalances store = new ReadOnlySeasonBalances(r.from);
        List<LedgerAuditor.Mismatch> mismatches = new ArrayList<>();
        for (Currency currency : List.of(Currency.GOLD, Currency.MULBINEUL)) {
            mismatches.addAll(LedgerAuditor.audit(db, currency, store));
        }
        if (!mismatches.isEmpty()) {
            List<String> sample =
                    mismatches.stream()
                            .limit(10)
                            .map(
                                    m ->
                                            m.player()
                                                    + " "
                                                    + m.currency()
                                                    + " 원장 "
                                                    + m.ledgerSum()
                                                    + " / 잔액 "
                                                    + m.balance())
                            .toList();
            throw new TransitionException(
                    "원장 불일치 " + mismatches.size() + "건: " + String.join(", ", sample));
        }
        return Result.of(0, "원장 일치");
    }

    // ------------------------------------------------------------------ 5. BACKUP

    private Result backup(Run r) throws Exception {
        Instant frozen = freezeTime(journal.rows(r.id));
        r.status = Status.WAITING_BACKUP;
        env.progress(
                "season.transition.waiting_backup",
                Map.of("marker", env.dataPath(r.plan.transition().backupMarker()).toString()));
        String confirmer;
        try {
            confirmer = r.backupConfirmed.get();
        } catch (ExecutionException | CancellationException e) {
            throw new TransitionException("백업 확인이 취소되었습니다");
        } finally {
            if (r.status == Status.WAITING_BACKUP) {
                r.status = Status.RUNNING;
            }
        }
        Optional<String> marker = checkMarker(r.plan, frozen);
        if (marker.isPresent()) {
            throw new TransitionException(marker.get());
        }
        return Result.of(0, "확인: " + confirmer);
    }

    private static Instant freezeTime(List<LogRow> rows) {
        return rows.stream()
                .filter(row -> row.step().equals(TransitionStep.FREEZE.name()) && row.done())
                .map(row -> row.finishedAt() == null ? row.startedAt() : row.finishedAt())
                .findFirst()
                .orElse(Instant.EPOCH);
    }

    private Optional<String> checkMarker(ResetPlan plan, Instant frozen) {
        Path marker = env.dataPath(plan.transition().backupMarker());
        try {
            if (!Files.isRegularFile(marker)) {
                return Optional.of("백업 표식 파일이 없습니다: " + marker);
            }
            Instant modified = Files.getLastModifiedTime(marker).toInstant();
            if (!modified.isAfter(frozen)) {
                return Optional.of("백업 표식이 FREEZE(" + frozen + ") 이전 것입니다: " + modified);
            }
        } catch (IOException e) {
            return Optional.of("백업 표식을 읽지 못했습니다: " + e.getMessage());
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 6. ARCHIVE

    private record Checksum(long rows, long crc) {}

    private Result archive(Run r) throws Exception {
        db.txVoid(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(ArchiveSql.createSchema(r.from))) {
                        ps.executeUpdate();
                    }
                });
        long total = 0;
        long crc = 0;
        int batch = r.plan.transition().batchRows();
        for (ResetPlan.TableRule rule : r.plan.tables()) {
            if (!rule.policy().archives()) {
                continue;
            }
            String sub = "ARCHIVE:" + rule.table();
            if (TransitionProgress.isDone(journal.rows(r.id), sub)) {
                continue;
            }
            journal.begin(r.id, r.from, r.to, sub, r.operator);
            boolean exists = db.tx(c -> SchemaInspector.tableExists(c, null, rule.table()));
            if (!exists) {
                journal.finish(r.id, sub, LogRow.SKIPPED, 0, 0, "테이블 없음");
                continue;
            }
            List<String> columns = db.tx(c -> SchemaInspector.columns(c, rule.table()));
            List<String> pk = db.tx(c -> SchemaInspector.primaryKey(c, rule.table()));
            db.txVoid(
                    c -> {
                        try (PreparedStatement ps =
                                c.prepareStatement(ArchiveSql.createLike(r.from, rule.table()))) {
                            ps.executeUpdate();
                        }
                    });
            Checksum source = checksum(null, rule, columns, r.from);
            Checksum copy = checksum(ArchiveSql.archiveSchema(r.from), rule, columns, r.from);
            String detail;
            if (!source.equals(copy)) {
                if (copy.rows() > 0) {
                    db.txVoid(c -> update(c, ArchiveSql.clearArchive(r.from, rule), rule, r.from));
                }
                long offset = 0;
                while (true) {
                    long from = offset;
                    int n =
                            db.tx(
                                    c -> {
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        ArchiveSql.copyBatch(r.from, rule, pk))) {
                                            int i = 1;
                                            if (ArchiveSql.hasSeasonParam(rule)) {
                                                ps.setInt(i++, r.from);
                                            }
                                            ps.setInt(i++, batch);
                                            ps.setLong(i, from);
                                            return ps.executeUpdate();
                                        }
                                    });
                    offset += n;
                    if (n < batch) {
                        break;
                    }
                }
                copy = checksum(ArchiveSql.archiveSchema(r.from), rule, columns, r.from);
                if (!source.equals(copy)) {
                    journal.finish(
                            r.id,
                            sub,
                            LogRow.FAILED,
                            copy.rows(),
                            copy.crc(),
                            "원본 " + source + " / 복사 " + copy);
                    throw new TransitionException(
                            rule.table()
                                    + " 아카이브 검증 실패: 원본 "
                                    + source.rows()
                                    + "행/"
                                    + source.crc()
                                    + ", 복사 "
                                    + copy.rows()
                                    + "행/"
                                    + copy.crc());
                }
                detail = "복사 " + copy.rows() + "행";
            } else {
                detail = "이미 복사됨 " + copy.rows() + "행";
            }
            journal.finish(r.id, sub, LogRow.DONE, copy.rows(), copy.crc(), detail);
            total += copy.rows();
            crc += copy.crc();
        }
        runHooks(r, "archive", false);
        return new Result(total, crc, "아카이브 " + total + "행");
    }

    private Checksum checksum(
            @Nullable String schema, ResetPlan.TableRule rule, List<String> columns, int season)
            throws SQLException {
        return db.tx(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(ArchiveSql.checksum(schema, rule, columns))) {
                        if (ArchiveSql.hasSeasonParam(rule)) {
                            ps.setInt(1, season);
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            return new Checksum(rs.getLong(1), rs.getLong(2));
                        }
                    }
                });
    }

    private static int update(Connection c, String sql, ResetPlan.TableRule rule, int season)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (ArchiveSql.hasSeasonParam(rule)) {
                ps.setInt(1, season);
            }
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 7. SNAPSHOT

    private Result snapshot(Run r) throws Exception {
        String schema = ArchiveSql.archiveSchema(r.from);
        record Out(int rankings, Map<String, Integer> hof) {}
        Out out =
                db.tx(
                        c ->
                                new Out(
                                        env.snapshotRankings(c, r.from, schema),
                                        env.finalizeHallOfFame(c, r.from, schema)));
        long hofRows = out.hof().values().stream().mapToLong(Integer::longValue).sum();
        return Result.of(
                out.rankings() + hofRows, "랭킹 " + out.rankings() + "행, 명예의 전당 " + out.hof());
    }

    // ------------------------------------------------------------------ 8. RESET

    private Result reset(Run r) throws Exception {
        if (env.currentSeason() != r.from) {
            throw new TransitionException("서버 시즌이 바뀌었습니다. RESET은 시즌 " + r.from + " 서버에서만 합니다");
        }
        long total = 0;
        int batch = r.plan.transition().batchRows();
        String currencySub = "RESET:currency";
        if (!TransitionProgress.isDone(journal.rows(r.id), currencySub)) {
            journal.begin(r.id, r.from, r.to, currencySub, r.operator);
            long n =
                    r.plan.currencies().isEmpty()
                            ? 0
                            : env.resetBalances(r.from, r.plan.currencies());
            journal.finish(r.id, currencySub, LogRow.DONE, n, 0, "SEASON_RESET " + n + "건");
        }
        for (ResetPlan.TableRule rule : r.plan.tables()) {
            if (rule.policy() != ResetPlan.Policy.ARCHIVE_RESET) {
                continue;
            }
            String sub = "RESET:" + rule.table();
            List<LogRow> rows = journal.rows(r.id);
            if (TransitionProgress.isDone(rows, sub)) {
                continue;
            }
            journal.begin(r.id, r.from, r.to, sub, r.operator);
            if (!db.tx(c -> SchemaInspector.tableExists(c, null, rule.table()))) {
                journal.finish(r.id, sub, LogRow.SKIPPED, 0, 0, "테이블 없음");
                continue;
            }
            if (!TransitionProgress.isDone(rows, "ARCHIVE:" + rule.table())) {
                throw new TransitionException(rule.table() + "의 아카이브가 끝나지 않아 지울 수 없습니다");
            }
            long deleted = 0;
            while (true) {
                int n =
                        db.tx(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(ArchiveSql.deleteBatch(rule))) {
                                        int i = 1;
                                        if (ArchiveSql.hasSeasonParam(rule)) {
                                            ps.setInt(i++, r.from);
                                        }
                                        ps.setInt(i, batch);
                                        return ps.executeUpdate();
                                    }
                                });
                deleted += n;
                if (n < batch) {
                    break;
                }
            }
            journal.finish(r.id, sub, LogRow.DONE, deleted, 0, "삭제 " + deleted + "행");
            total += deleted;
        }
        List<String> states = r.plan.fishLedgerStates();
        String ledgerSub = "RESET:fish_ledger";
        if (!states.isEmpty() && !TransitionProgress.isDone(journal.rows(r.id), ledgerSub)) {
            journal.begin(r.id, r.from, r.to, ledgerSub, r.operator);
            long changed = 0;
            if (db.tx(c -> SchemaInspector.tableExists(c, null, "fish_ledger"))) {
                while (true) {
                    int n =
                            db.tx(
                                    c -> {
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        ArchiveSql.archiveFishLedger(
                                                                states.size()))) {
                                            int i = 1;
                                            ps.setInt(i++, r.from);
                                            for (String s : states) {
                                                ps.setString(i++, s);
                                            }
                                            ps.setInt(i, batch);
                                            return ps.executeUpdate();
                                        }
                                    });
                    changed += n;
                    if (n < batch) {
                        break;
                    }
                }
            }
            journal.finish(r.id, ledgerSub, LogRow.DONE, changed, 0, "ARCHIVED " + changed + "행");
        }
        runHooks(r, "reset", false);
        return Result.of(total, "삭제 " + total + "행");
    }

    // ------------------------------------------------------------------ 9. CONTENT

    private Result content(Run r) throws Exception {
        List<String> errors = env.switchContent(r.to);
        if (!errors.isEmpty()) {
            throw new TransitionException("CONTENT 실패: " + String.join(" / ", errors));
        }
        return Result.of(0, "시즌 " + r.to + " 콘텐츠 준비");
    }

    // ------------------------------------------------------------------ 10. OPEN

    private Result open(Run r) throws Exception {
        TransitionEnvironment.SeasonWindow window = env.window(r.to);
        db.txVoid(
                c -> {
                    SeasonRepository.transition(
                            c, r.from, SeasonPhase.TRANSITIONING, SeasonPhase.ARCHIVED);
                    SeasonRepository.ensureMeta(
                            c,
                            r.to,
                            Instant.ofEpochSecond(window.startsAt()),
                            Instant.ofEpochSecond(window.endsAt()),
                            SeasonPhase.PLANNED);
                    SeasonRepository.transition(c, r.to, SeasonPhase.PLANNED, SeasonPhase.ACTIVE);
                    Optional<SeasonMeta> to = SeasonRepository.meta(c, r.to);
                    if (to.isEmpty() || to.get().state() != SeasonPhase.ACTIVE) {
                        throw new SQLException("시즌 " + r.to + "을(를) ACTIVE로 바꾸지 못했습니다");
                    }
                });
        long granted = 0;
        for (ResetPlan.OpenTitle t : r.plan.openTitles()) {
            if (t.fromSeason() != 0 && t.fromSeason() != r.from) {
                continue;
            }
            String sub = "OPEN:title:" + t.title().shortName();
            if (TransitionProgress.isDone(journal.rows(r.id), sub)) {
                continue;
            }
            journal.begin(r.id, r.from, r.to, sub, r.operator);
            String schema = ArchiveSql.archiveSchema(r.from);
            int n =
                    db.tx(
                            c -> {
                                Set<UUID> players = new HashSet<>();
                                String table =
                                        SchemaInspector.tableExists(c, schema, "player_season")
                                                ? ArchiveSql.qualified(schema, "player_season")
                                                : ArchiveSql.ident("player_season");
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "SELECT DISTINCT uuid FROM "
                                                        + table
                                                        + " WHERE season = ?")) {
                                    ps.setInt(1, r.from);
                                    try (ResultSet rs = ps.executeQuery()) {
                                        while (rs.next()) {
                                            players.add(UUID.fromString(rs.getString(1)));
                                        }
                                    }
                                }
                                int count = 0;
                                for (UUID uuid : players) {
                                    if (TitleStore.insert(
                                            c,
                                            uuid,
                                            t.title(),
                                            r.to,
                                            "open:" + r.to + ":" + t.title().shortName())) {
                                        count++;
                                    }
                                }
                                return count;
                            });
            journal.finish(r.id, sub, LogRow.DONE, n, 0, t.title() + " " + n + "명");
            granted += n;
        }
        runHooks(r, "open", false);
        env.metaChanged(r.from);
        env.metaChanged(r.to);
        return Result.of(granted, "시즌 " + r.to + " ACTIVE, 칭호 " + granted + "명");
    }

    // ------------------------------------------------------------------ 훅

    private void runHooks(Run r, String phase, boolean dryRun) throws Exception {
        for (SeasonHook hook : env.hooks()) {
            String sub = "HOOK:" + hook.id() + ":" + phase;
            if (TransitionProgress.isDone(journal.rows(r.id), sub)) {
                continue;
            }
            journal.begin(r.id, r.from, r.to, sub, r.operator);
            List<String> notes = new ArrayList<>();
            HookContext ctx =
                    new HookContext(
                            r.id,
                            r.from,
                            r.to,
                            dryRun,
                            db,
                            m -> {
                                notes.add(m);
                                LOG.log(Level.INFO, "[{0}] {1}", hook.id(), m);
                            });
            try {
                switch (phase) {
                    case "archive" -> hook.onArchive(ctx);
                    case "reset" -> hook.onReset(ctx);
                    default -> hook.onOpen(ctx);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                journal.finish(r.id, sub, LogRow.FAILED, 0, 0, String.valueOf(e.getMessage()));
                throw new TransitionException(
                        "훅 " + hook.id() + "." + phase + " 실패: " + e.getMessage());
            }
            journal.finish(r.id, sub, LogRow.DONE, notes.size(), 0, String.join(" / ", notes));
        }
    }

    // ------------------------------------------------------------------ dry-run

    /** 실제 쓰기 없이 검사와 예정 행 수, 예상 시간 (블로킹, docs 20 11.4). */
    public DryRunReport dryRun() throws SQLException {
        ResetPlan plan = plans.get();
        int from = env.currentSeason();
        int to = from + 1;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        precheck(plan, from, to, errors, warnings);
        List<DryRunReport.TablePlan> tables = new ArrayList<>();
        long work = 0;
        for (ResetPlan.TableRule rule : plan.tables()) {
            DryRunReport.TablePlan tp =
                    db.tx(
                            c -> {
                                if (!SchemaInspector.tableExists(c, null, rule.table())) {
                                    return new DryRunReport.TablePlan(
                                            rule.table(), rule.policy().key(), false, 0, 0);
                                }
                                long archive = 0;
                                long delete = 0;
                                if (rule.policy().archives()) {
                                    archive =
                                            count(
                                                    c,
                                                    "SELECT COUNT(*) FROM "
                                                            + ArchiveSql.ident(rule.table())
                                                            + " WHERE "
                                                            + ArchiveSql.where(rule),
                                                    rule,
                                                    from);
                                }
                                if (rule.policy() == ResetPlan.Policy.ARCHIVE_RESET) {
                                    try {
                                        delete = count(c, ArchiveSql.countReset(rule), rule, from);
                                    } catch (SQLException e) {
                                        delete = -1;
                                    }
                                }
                                return new DryRunReport.TablePlan(
                                        rule.table(), rule.policy().key(), true, archive, delete);
                            });
            tables.add(tp);
            work += tp.archiveRows() * 2 + Math.max(0, tp.deleteRows());
        }
        long balances =
                plan.currencies().isEmpty()
                        ? 0
                        : db.tx(
                                c -> {
                                    StringBuilder cond = new StringBuilder();
                                    for (Currency cur : plan.currencies()) {
                                        if (!cond.isEmpty()) {
                                            cond.append(" + ");
                                        }
                                        cond.append("((")
                                                .append(ReadOnlySeasonBalances.column(cur))
                                                .append(") <> 0)");
                                    }
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT COALESCE(SUM("
                                                            + cond
                                                            + "), 0) FROM player_season WHERE season = ?")) {
                                        ps.setInt(1, from);
                                        try (ResultSet rs = ps.executeQuery()) {
                                            return rs.next() ? rs.getLong(1) : 0L;
                                        }
                                    }
                                });
        long fishRows = 0;
        if (!plan.fishLedgerStates().isEmpty()) {
            fishRows =
                    db.tx(
                            c -> {
                                if (!SchemaInspector.tableExists(c, null, "fish_ledger")) {
                                    return 0L;
                                }
                                String marks =
                                        String.join(
                                                ", ",
                                                Collections.nCopies(
                                                        plan.fishLedgerStates().size(), "?"));
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "SELECT COUNT(*) FROM fish_ledger WHERE season = ? AND state IN ("
                                                        + marks
                                                        + ")")) {
                                    int i = 1;
                                    ps.setInt(i++, from);
                                    for (String s : plan.fishLedgerStates()) {
                                        ps.setString(i++, s);
                                    }
                                    try (ResultSet rs = ps.executeQuery()) {
                                        return rs.next() ? rs.getLong(1) : 0L;
                                    }
                                }
                            });
        }
        List<String> hookNotes = new ArrayList<>();
        for (SeasonHook hook : env.hooks()) {
            HookContext ctx =
                    new HookContext(
                            "dry-run",
                            from,
                            to,
                            true,
                            db,
                            m -> hookNotes.add(hook.id() + ": " + m));
            try {
                hook.onArchive(ctx);
                hook.onReset(ctx);
                hook.onOpen(ctx);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add("훅 " + hook.id() + " dry-run이 중단되었습니다");
                break;
            } catch (Exception e) {
                errors.add("훅 " + hook.id() + " dry-run 오류: " + e.getMessage());
            }
        }
        long seconds =
                (work + balances * 10 + fishRows) / Math.max(1, plan.transition().rowsPerSecond());
        if (seconds > plan.transition().maxMinutes() * 60L) {
            warnings.add(
                    "예상 소요 "
                            + (seconds / 60)
                            + "분이 목표 "
                            + plan.transition().maxMinutes()
                            + "분을 넘습니다");
        }
        return new DryRunReport(errors, warnings, tables, balances, fishRows, hookNotes, seconds);
    }

    private static long count(Connection c, String sql, ResetPlan.TableRule rule, int season)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (ArchiveSql.hasSeasonParam(rule)) {
                ps.setInt(1, season);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
