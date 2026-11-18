package wiki.creeper.harbor.boss.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.id.BossId;

/**
 * 보스별 최단 처치 기록 조회 (docs 20 10.2). CLEARED 기록은 Boss가 Season HallOfFameService.submit으로 넘기고, Season
 * 랭킹은 boss_records 테이블을 직접 읽는다. 결과 future는 메인 스레드에서 완료된다.
 */
public interface BossRecordService {

    /** 시즌 기록 (짧은 순, 최대 limit개). */
    CompletableFuture<List<BossRecord>> top(BossId boss, int season, int limit);

    /** 모든 시즌을 통틀어 기록 (이월 테이블이라 지난 시즌도 남아 있다). */
    CompletableFuture<List<BossRecord>> allTime(BossId boss, int limit);
}
