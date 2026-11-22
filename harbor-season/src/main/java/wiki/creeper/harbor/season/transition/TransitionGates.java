package wiki.creeper.harbor.season.transition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.ledger.LedgerAuditor;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.data.SeasonMeta;
import wiki.creeper.harbor.season.data.SeasonRepository;

/**
 * Transition steps that do not move data: 2 NOTICE (notice and wait), 3 FREEZE (TRANSITIONING,
 * server freeze), 4 VERIFY (ledger check), 5 BACKUP (wait for backup confirmation, marker check), 9
 * CONTENT (new season content). Runs blocking on the transition thread.
 */
final class TransitionGates {

    private final Database db;
    private final TransitionEnvironment env;
    private final TransitionLog journal;

    TransitionGates(Database db, TransitionEnvironment env, TransitionLog journal) {
        this.db = db;
        this.env = env;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ 2. NOTICE

    StepResult notice(TransitionEngine.Run r) throws Exception {
        List<Integer> minutes = r.plan.transition().noticeMinutes();
        if (minutes.isEmpty()) {
            return StepResult.of(0, null);
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
        return StepResult.of(sent, "공지 " + sent + "회");
    }

    private static void sleepUntil(TransitionEngine.Run r, Instant at)
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

    StepResult freeze(TransitionEngine.Run r) throws Exception {
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
        return StepResult.of(
                result.servers().size(),
                (changed ? "TRANSITIONING, " : "") + "서버 " + String.join(", ", result.servers()));
    }

    // ------------------------------------------------------------------ 4. VERIFY

    StepResult verify(TransitionEngine.Run r) throws Exception {
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
        return StepResult.of(0, "원장 일치");
    }

    // ------------------------------------------------------------------ 5. BACKUP

    StepResult backup(TransitionEngine.Run r) throws Exception {
        Instant frozen = freezeTime(journal.rows(r.id));
        r.status = TransitionEngine.Status.WAITING_BACKUP;
        env.progress(
                "season.transition.waiting_backup",
                Map.of("marker", env.dataPath(r.plan.transition().backupMarker()).toString()));
        String confirmer;
        try {
            confirmer = r.backupConfirmed.get();
        } catch (ExecutionException | CancellationException e) {
            throw new TransitionException("백업 확인이 취소되었습니다");
        } finally {
            if (r.status == TransitionEngine.Status.WAITING_BACKUP) {
                r.status = TransitionEngine.Status.RUNNING;
            }
        }
        Optional<String> marker = checkMarker(r.plan, frozen);
        if (marker.isPresent()) {
            throw new TransitionException(marker.get());
        }
        return StepResult.of(0, "확인: " + confirmer);
    }

    static Instant freezeTime(List<LogRow> rows) {
        return rows.stream()
                .filter(row -> row.step().equals(TransitionStep.FREEZE.name()) && row.done())
                .map(row -> row.finishedAt() == null ? row.startedAt() : row.finishedAt())
                .findFirst()
                .orElse(Instant.EPOCH);
    }

    Optional<String> checkMarker(ResetPlan plan, Instant frozen) {
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

    // ------------------------------------------------------------------ 9. CONTENT

    StepResult content(TransitionEngine.Run r) throws Exception {
        List<String> errors = env.switchContent(r.to);
        if (!errors.isEmpty()) {
            throw new TransitionException("CONTENT 실패: " + String.join(" / ", errors));
        }
        return StepResult.of(0, "시즌 " + r.to + " 콘텐츠 준비");
    }
}
