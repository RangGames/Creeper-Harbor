package wiki.creeper.harbor.jobs.api;

import java.time.Duration;
import java.util.Optional;
import wiki.creeper.harbor.common.id.SkillId;

/**
 * Capstone state.
 *
 * @param node owned capstone node (empty if none)
 * @param cooldownLeft remaining cooldown
 * @param weeklyUsesLeft uses left this week (-1 for capstones without a weekly limit)
 */
public record CapstoneState(Optional<SkillId> node, Duration cooldownLeft, int weeklyUsesLeft) {}
