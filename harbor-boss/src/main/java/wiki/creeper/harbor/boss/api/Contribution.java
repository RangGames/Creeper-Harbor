package wiki.creeper.harbor.boss.api;

import java.util.UUID;

/**
 * Contribution of one participant (one boss_participation row).
 *
 * @param tension time spent holding tension in the safe zone (seconds)
 * @param repair durability restored + quartermaster support amount
 * @param evasion successful dodges + telegraph area dodges
 * @param rank starting at 1
 * @param top eligible for the top 20% bonus reward
 * @param eligible whether minimum participation was met
 */
public record Contribution(
        UUID player,
        BossRole role,
        double damage,
        double tension,
        double repair,
        double evasion,
        double score,
        int rank,
        boolean top,
        boolean eligible) {}
