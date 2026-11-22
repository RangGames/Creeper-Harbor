package wiki.creeper.harbor.jobs.api;

import java.util.Optional;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;

/**
 * Job state.
 *
 * @param exp EXP accumulated within the current level
 * @param nextLevelExp EXP needed for the next level (0 at max level)
 * @param levelCap level cap of the current tier (1st 30, 2nd 60, 3rd 70)
 */
public record JobState(
        Optional<JobLine> line,
        JobTier tier,
        int level,
        long exp,
        long nextLevelExp,
        int levelCap) {}
