package wiki.creeper.harbor.season.transition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.season.content.ResetPlan;

class TransitionLogicTest {

    private static LogRow row(String step, String state) {
        return new LogRow("t", 1, 2, step, state, 0, 0, null, Instant.EPOCH, null, "CONSOLE");
    }

    private static ResetPlan.TableRule rule(
            String table, ResetPlan.Scope scope, ResetPlan.Policy policy, String keep) {
        return new ResetPlan.TableRule(table, scope, policy, "season", keep);
    }

    private static ResetPlan plan(ResetPlan.TableRule... rules) {
        ResetPlan empty = ResetPlan.empty();
        return new ResetPlan(
                List.of(rules), Set.of(), List.of(), List.of(), empty.gate(), empty.transition());
    }

    @Test
    void stepsAreOrdered() {
        assertEquals(Optional.of(TransitionStep.NOTICE), TransitionStep.PRECHECK.next());
        assertEquals(Optional.empty(), TransitionStep.OPEN.next());
        assertEquals(10, TransitionStep.values().length);
        assertFalse(TransitionStep.SNAPSHOT.pastRollbackA());
        assertTrue(TransitionStep.RESET.pastRollbackA());
    }

    @Test
    void resumeStartsAfterLastDoneStep() {
        List<LogRow> rows = new ArrayList<>();
        assertEquals(Optional.of(TransitionStep.PRECHECK), TransitionProgress.next(rows));
        rows.add(row("PRECHECK", LogRow.DONE));
        rows.add(row("NOTICE", LogRow.DONE));
        rows.add(row("FREEZE", LogRow.STARTED));
        assertEquals(Optional.of(TransitionStep.FREEZE), TransitionProgress.next(rows));
        rows.add(row("FREEZE", LogRow.DONE));
        rows.add(row("VERIFY", LogRow.DONE));
        rows.add(row("BACKUP", LogRow.DONE));
        rows.add(row("ARCHIVE:player_season", LogRow.DONE));
        rows.add(row("ARCHIVE", LogRow.FAILED));
        assertEquals(Optional.of(TransitionStep.ARCHIVE), TransitionProgress.next(rows));
        assertTrue(TransitionProgress.isDone(rows, "ARCHIVE:player_season"));
        assertFalse(TransitionProgress.pastRollbackA(rows));
        rows.add(row("RESET:currency", LogRow.STARTED));
        assertTrue(TransitionProgress.pastRollbackA(rows));
    }

    @Test
    void finishedTransitionHasNoNextStep() {
        List<LogRow> rows = new ArrayList<>();
        for (TransitionStep s : TransitionStep.values()) {
            rows.add(row(s.name(), LogRow.DONE));
        }
        assertEquals(Optional.empty(), TransitionProgress.next(rows));
    }

    @Test
    void coverageFindsMissingSeasonTables() {
        ResetPlan p =
                plan(
                        rule(
                                "player_season",
                                ResetPlan.Scope.SEASON_COLUMN,
                                ResetPlan.Policy.ARCHIVE_RESET,
                                null));
        assertEquals(
                List.of("jobs_state", "records"),
                PlanCoverage.missing(p, Set.of("player_season", "records", "jobs_state")));
        assertEquals(List.of("player_season"), PlanCoverage.absent(p, Set.of("records")));
    }

    @Test
    void archiveSqlKeepsMainQuestRows() {
        ResetPlan.TableRule quest =
                rule(
                        "quest_progress",
                        ResetPlan.Scope.SEASON_COLUMN,
                        ResetPlan.Policy.ARCHIVE_RESET,
                        "quest_id LIKE 'harbor:quest/main/%'");
        assertEquals(
                "DELETE FROM `quest_progress` WHERE `season` = ? AND NOT (quest_id LIKE 'harbor:quest/main/%') LIMIT ?",
                ArchiveSql.deleteBatch(quest));
        ResetPlan.TableRule auction =
                rule("auction_listing", ResetPlan.Scope.FULL, ResetPlan.Policy.ARCHIVE_RESET, null);
        assertEquals(
                "DELETE FROM `auction_listing` WHERE 1 = 1 LIMIT ?",
                ArchiveSql.deleteBatch(auction));
        assertFalse(ArchiveSql.hasSeasonParam(auction));
        assertEquals(
                "CREATE TABLE IF NOT EXISTS `archive_s3`.`player_season` LIKE `player_season`",
                ArchiveSql.createLike(3, "player_season"));
        assertEquals(
                "INSERT INTO `archive_s3`.`quest_progress` SELECT * FROM `quest_progress` WHERE `season` = ? ORDER BY `uuid`, `season` LIMIT ? OFFSET ?",
                ArchiveSql.copyBatch(3, quest, List.of("uuid", "season")));
        String crc = ArchiveSql.checksum("archive_s3", quest, List.of("uuid", "state"));
        assertTrue(
                crc.startsWith("SELECT COUNT(*), COALESCE(SUM(CRC32(CONCAT_WS('|', IFNULL(`uuid`"),
                crc);
        assertTrue(crc.contains("FROM `archive_s3`.`quest_progress` WHERE `season` = ?"), crc);
        assertThrows(IllegalArgumentException.class, () -> ArchiveSql.ident("players`; DROP"));
    }

    @Test
    void balanceColumns() {
        assertEquals(
                "gold",
                ReadOnlySeasonBalances.column(wiki.creeper.harbor.common.model.Currency.GOLD));
        assertEquals(
                "mulbineul_earned - mulbineul_spent",
                ReadOnlySeasonBalances.column(wiki.creeper.harbor.common.model.Currency.MULBINEUL));
    }
}
