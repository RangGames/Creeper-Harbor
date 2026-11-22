package wiki.creeper.harbor.jobs.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Quality;

/**
 * Harbor-Jobs public API (docs 20 2.2). Other modules only look at the modifier values Jobs
 * publishes and the quality caps this service reports, and never compute job rules themselves. Use
 * on the main thread.
 */
public interface JobsService {

    /** Job state of an online player. */
    JobState state(UUID player);

    /** Quality cap per job tier (1st ★3, 2nd ★4, 3rd ★5). */
    Quality qualityCap(UUID player);

    /**
     * Grants after applying decay, catch-up and exp_gain, and returns the amount actually granted.
     */
    long grantExp(UUID player, ExpGrant grant);

    /**
     * Opens the job advancement confirmation screen and returns the result (Quest's advancement
     * quest report step). For the 2nd tier, line selection comes first.
     */
    CompletableFuture<AdvanceResult> requestAdvance(Player player, JobTier target);

    /**
     * Grants mulbineul and returns true on the first ★4 (2nd tier) or ★5 (3rd tier) after a job
     * advancement (called by Fishing on catch).
     */
    boolean claimTierFirstQuality(UUID player, Quality quality);

    CapstoneState capstone(UUID player);

    /** Uses a weekly capstone (big spender deal) once. False if no uses are left (Economy). */
    CompletableFuture<Boolean> consumeWeeklyUse(UUID player);
}
