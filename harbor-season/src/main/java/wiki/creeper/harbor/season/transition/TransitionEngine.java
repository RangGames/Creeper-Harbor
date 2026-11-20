package wiki.creeper.harbor.season.transition;

import java.lang.System.Logger.Level;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.data.SeasonRepository;

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
 *
 * <p>여기서는 명령·단계 순서·잠금·실패 처리만 하고, 단계 본문은 {@link TransitionPreflight}(1단계·dry-run), {@link
 * TransitionGates}(2~5·9단계), {@link TransitionDataSteps}(6~8·10단계와 훅)가 맡는다.
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
    private final TransitionPreflight preflight;
    private final TransitionGates gates;
    private final TransitionDataSteps data;
    private volatile @Nullable Run run;

    static final class Run {
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
        this.preflight = new TransitionPreflight(db, env, plans);
        this.gates = new TransitionGates(db, env, journal);
        this.data = new TransitionDataSteps(db, env, journal);
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
            Instant frozen = TransitionGates.freezeTime(journal.rows(r.id));
            Optional<String> marker = gates.checkMarker(r.plan, frozen);
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
                StepResult result = runStep(r, s);
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

    private StepResult runStep(Run r, TransitionStep step) throws Exception {
        return switch (step) {
            case PRECHECK -> preflight.precheckStep(r);
            case NOTICE -> gates.notice(r);
            case FREEZE -> gates.freeze(r);
            case VERIFY -> gates.verify(r);
            case BACKUP -> gates.backup(r);
            case ARCHIVE -> data.archive(r);
            case SNAPSHOT -> data.snapshot(r);
            case RESET -> data.reset(r);
            case CONTENT -> gates.content(r);
            case OPEN -> data.open(r);
        };
    }

    // ------------------------------------------------------------------ dry-run

    /** 실제 쓰기 없이 검사와 예정 행 수, 예상 시간 (블로킹, docs 20 11.4). */
    public DryRunReport dryRun() throws SQLException {
        return preflight.dryRun();
    }
}
