package wiki.creeper.harbor.season.transition;

import java.util.List;

/**
 * dry-run 결과 (docs 20 11.4: 실제 쓰기 없이 PRECHECK와 테이블별 아카이브·삭제 예정 행 수, 예상 시간).
 *
 * @param errors PRECHECK 오류 (있으면 전환할 수 없음)
 * @param warnings 경고
 * @param tables 테이블별 예정
 * @param balanceResets SEASON_RESET 원장 기록 예정 수
 * @param fishLedgerRows ARCHIVED로 바꿀 fish_ledger 행 수
 * @param hookNotes 훅이 알린 할 일
 * @param estimatedSeconds 예상 소요 시간 (공지 대기 제외)
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

    /** 테이블 하나의 예정. 없는 테이블이면 exists = false. */
    public record TablePlan(
            String table, String policy, boolean exists, long archiveRows, long deleteRows) {}
}
