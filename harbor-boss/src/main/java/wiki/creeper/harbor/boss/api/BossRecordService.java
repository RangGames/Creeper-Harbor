package wiki.creeper.harbor.boss.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.id.BossId;

/**
 * Fastest kill record lookup per boss (docs 20 10.2). Boss hands CLEARED records to Season
 * HallOfFameService.submit, and Season rankings read the boss_records table directly. Result
 * futures complete on the main thread.
 */
public interface BossRecordService {

    /** Season records (shortest first, at most limit entries). */
    CompletableFuture<List<BossRecord>> top(BossId boss, int season, int limit);

    /** Records across all seasons (a carry-over table, so past seasons remain). */
    CompletableFuture<List<BossRecord>> allTime(BossId boss, int limit);
}
