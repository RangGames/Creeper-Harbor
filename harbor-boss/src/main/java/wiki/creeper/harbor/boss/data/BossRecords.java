package wiki.creeper.harbor.boss.data;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import wiki.creeper.harbor.boss.api.BossRecord;
import wiki.creeper.harbor.boss.api.BossRecordService;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.id.BossId;

/** BossRecordService implementation (reads boss_records). */
public final class BossRecords implements BossRecordService {

    private final Database db;
    private final Executor main;

    public BossRecords(Database db, Executor main) {
        this.db = db;
        this.main = main;
    }

    @Override
    public CompletableFuture<List<BossRecord>> top(BossId boss, int season, int limit) {
        return db.async(c -> BossRepository.records(c, boss, season, limit))
                .thenApplyAsync(List::copyOf, main);
    }

    @Override
    public CompletableFuture<List<BossRecord>> allTime(BossId boss, int limit) {
        return db.async(c -> BossRepository.records(c, boss, null, limit))
                .thenApplyAsync(List::copyOf, main);
    }
}
