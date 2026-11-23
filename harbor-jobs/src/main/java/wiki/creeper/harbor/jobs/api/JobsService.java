package wiki.creeper.harbor.jobs.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.JobLine;
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

    // ------------------------------------------------------------------ Skills

    /** Every skill node in content. */
    List<SkillNodeInfo> skillNodes();

    Optional<SkillNodeInfo> skillNode(SkillId node);

    /** Ranks of the nodes an online player has invested in (empty if not loaded). */
    Map<SkillId, Integer> skills(UUID player);

    /** Investment points available (the player's mulbineul balance; 0 if not loaded). */
    long skillPoints(UUID player);

    /** Whether one more rank can be invested now (same checks as the skill menu). */
    InvestResult checkInvest(UUID player, SkillId node);

    /**
     * Invests one rank like the skill menu: mulbineul deduction (SKILL_INVEST ledger) and the skill
     * row in one transaction, then SkillAllocatedEvent. Completes on the main thread.
     */
    CompletableFuture<InvestResult> invest(Player player, SkillId node);

    /**
     * Player common tree reset (NPC job:reset_skill): free once per season, then gold; common tree
     * investments refunded. Fires SkillResetEvent. False if it failed (not enough gold, busy).
     */
    CompletableFuture<Boolean> resetCommonSkills(UUID player);

    /**
     * Player line change (NPC job:change_line): gold cost, line trees refunded at the refund ratio,
     * capstone cleared. Fires JobLineChangedEvent. False for the 1st tier, the same line or a
     * failure.
     */
    CompletableFuture<Boolean> changeLine(UUID player, JobLine line);

    // ------------------------------------------------------------------ Admin
    // These methods do not write admin_log (commands and Harbor-Skript record their own entries).
    // Other callers must record the action via Harbor.api().adminLog().

    /**
     * Admin skill reset (/harbor jobs reset): scope common · line · all, no cost, optional 100%
     * refund. Fires SkillResetEvent. False for an unknown scope or a failure.
     */
    CompletableFuture<Boolean> adminResetSkills(UUID player, String scope, boolean refund);

    /**
     * Admin tier and line (/harbor jobs tier): no gold, the advancement follow-up (passives,
     * overflow, JobAdvancedEvent) runs for the 2nd and 3rd tier. A null line keeps the current one.
     */
    CompletableFuture<Boolean> adminSetTier(UUID player, JobTier tier, @Nullable JobLine line);

    /**
     * Admin level and EXP (/harbor jobs level): level clamped to 1..max level, EXP to below the
     * next level's need. Saved with the player data (no level-up rewards or events).
     *
     * @return false if the player is not loaded
     */
    boolean adminSetLevel(UUID player, int level, long exp);
}
