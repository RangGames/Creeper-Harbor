package wiki.creeper.harbor.season.transition;

import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.data.SeasonMeta;
import wiki.creeper.harbor.season.data.SeasonRepository;
import wiki.creeper.harbor.season.data.TitleStore;

/**
 * 데이터를 옮기는 전환 단계: 6 ARCHIVE(테이블별 복사·행 수·CRC32 검증), 7 SNAPSHOT(랭킹·명예의 전당), 8 RESET(잔액
 * SEASON_RESET·조건 삭제·fish_ledger 보관), 10 OPEN(새 시즌 ACTIVE·개방 칭호)과 모듈 훅. 하위 단계마다 일지에 남겨 다시 실행해도 결과가
 * 같다.
 */
final class TransitionDataSteps {

    private static final System.Logger LOG = System.getLogger("Harbor/SeasonTransition");

    private final Database db;
    private final TransitionEnvironment env;
    private final TransitionLog journal;

    TransitionDataSteps(Database db, TransitionEnvironment env, TransitionLog journal) {
        this.db = db;
        this.env = env;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ 6. ARCHIVE

    private record Checksum(long rows, long crc) {}

    StepResult archive(TransitionEngine.Run r) throws Exception {
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
        return new StepResult(total, crc, "아카이브 " + total + "행");
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

    StepResult snapshot(TransitionEngine.Run r) throws Exception {
        String schema = ArchiveSql.archiveSchema(r.from);
        record Out(int rankings, Map<String, Integer> hof) {}
        Out out =
                db.tx(
                        c ->
                                new Out(
                                        env.snapshotRankings(c, r.from, schema),
                                        env.finalizeHallOfFame(c, r.from, schema)));
        long hofRows = out.hof().values().stream().mapToLong(Integer::longValue).sum();
        return StepResult.of(
                out.rankings() + hofRows, "랭킹 " + out.rankings() + "행, 명예의 전당 " + out.hof());
    }

    // ------------------------------------------------------------------ 8. RESET

    StepResult reset(TransitionEngine.Run r) throws Exception {
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
        return StepResult.of(total, "삭제 " + total + "행");
    }

    // ------------------------------------------------------------------ 10. OPEN

    StepResult open(TransitionEngine.Run r) throws Exception {
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
        return StepResult.of(granted, "시즌 " + r.to + " ACTIVE, 칭호 " + granted + "명");
    }

    // ------------------------------------------------------------------ 훅

    private void runHooks(TransitionEngine.Run r, String phase, boolean dryRun) throws Exception {
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
}
