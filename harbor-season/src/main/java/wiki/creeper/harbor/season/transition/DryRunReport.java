package wiki.creeper.harbor.season.transition;

import java.util.List;

/**
 * dry-run result (docs 20 11.4: PRECHECK plus per-table planned archive and delete row counts and
 * estimated time, without any actual writes).
 *
 * @param errors PRECHECK errors (if any, the transition cannot run)
 * @param warnings warnings
 * @param tables per-table plan
 * @param balanceResets number of SEASON_RESET ledger entries planned
 * @param fishLedgerRows number of fish_ledger rows to change to ARCHIVED
 * @param hookNotes tasks reported by hooks
 * @param estimatedSeconds estimated duration (excluding the notice wait)
 */
public record DryRunReport(
        List<String> errors,
        List<String> warnings,
        List<TablePlan> tables,
        long balanceResets,
        long fishLedgerRows,
        List<String> hookNotes,
        long estimatedSeconds) {

    public DryRunReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        tables = List.copyOf(tables);
        hookNotes = List.copyOf(hookNotes);
    }

    /** Plan for one table. exists = false if the table does not exist. */
    public record TablePlan(
            String table, String policy, boolean exists, long archiveRows, long deleteRows) {}
}
