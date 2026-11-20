package wiki.creeper.harbor.season.transition;

import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.data.SeasonMeta;
import wiki.creeper.harbor.season.data.SeasonRepository;

/** 시즌 전환 사전 검사 (1단계 PRECHECK)와 dry-run 보고서. 쓰기 없이 DB·콘텐츠·디스크·계획을 확인한다. 블로킹. */
final class TransitionPreflight {

    private static final System.Logger LOG = System.getLogger("Harbor/SeasonTransition");

    private final Database db;
    private final TransitionEnvironment env;
    private final Supplier<ResetPlan> plans;

    TransitionPreflight(Database db, TransitionEnvironment env, Supplier<ResetPlan> plans) {
        this.db = db;
        this.env = env;
        this.plans = plans;
    }

    // ------------------------------------------------------------------ 1. PRECHECK

    StepResult precheckStep(TransitionEngine.Run r) throws Exception {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long size = precheck(r.plan, r.from, r.to, errors, warnings);
        warnings.forEach(w -> LOG.log(Level.WARNING, "PRECHECK 경고: " + w));
        if (!errors.isEmpty()) {
            throw new TransitionException(
                    "PRECHECK 실패 " + errors.size() + "건: " + String.join(" / ", errors));
        }
        return StepResult.of(size, "경고 " + warnings.size() + "건");
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

    // ------------------------------------------------------------------ dry-run

    /** 실제 쓰기 없이 검사와 예정 행 수, 예상 시간 (블로킹, docs 20 11.4). */
    DryRunReport dryRun() throws SQLException {
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
