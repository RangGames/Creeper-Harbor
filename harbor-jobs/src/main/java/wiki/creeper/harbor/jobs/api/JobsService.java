package wiki.creeper.harbor.jobs.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Quality;

/**
 * Harbor-Jobs 공개 API (docs 20 2.2). 다른 모듈은 Jobs가 올린 보정자 값과 이 서비스가 알려주는 품질 상한만 보고, 직업 규칙을 직접 계산하지
 * 않는다. 메인 스레드에서 쓴다.
 */
public interface JobsService {

    /** 접속 중인 플레이어의 직업 상태. */
    JobState state(UUID player);

    /** 직업 단계별 품질 상한 (1차 ★3, 2차 ★4, 3차 ★5). */
    Quality qualityCap(UUID player);

    /** 감쇠·캐치업·exp_gain을 적용해 지급하고 실제 지급량을 돌려준다. */
    long grantExp(UUID player, ExpGrant grant);

    /** 전직 확인 화면을 열고 결과를 돌려준다 (Quest의 전직 퀘스트 보고 단계). 2차는 계열 선택이 먼저 나온다. */
    CompletableFuture<AdvanceResult> requestAdvance(Player player, JobTier target);

    /** 전직 후 첫 ★4(2차)·★5(3차)면 물비늘을 지급하고 true (Fishing이 포획 때 호출). */
    boolean claimTierFirstQuality(UUID player, Quality quality);

    CapstoneState capstone(UUID player);

    /** 주간 사용 캡스톤(큰손 거래)을 한 번 쓴다. 남은 횟수가 없으면 false (Economy). */
    CompletableFuture<Boolean> consumeWeeklyUse(UUID player);
}
