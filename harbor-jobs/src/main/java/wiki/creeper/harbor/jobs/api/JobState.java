package wiki.creeper.harbor.jobs.api;

import java.util.Optional;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;

/**
 * 직업 상태.
 *
 * @param exp 현재 레벨 안에서 쌓인 EXP
 * @param nextLevelExp 다음 레벨까지 필요한 EXP (최대 레벨이면 0)
 * @param levelCap 지금 단계의 레벨 상한 (1차 30, 2차 60, 3차 70)
 */
public record JobState(
        Optional<JobLine> line,
        JobTier tier,
        int level,
        long exp,
        long nextLevelExp,
        int levelCap) {}
