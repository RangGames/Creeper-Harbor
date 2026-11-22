package wiki.creeper.harbor.npc.api;

import java.util.List;

/**
 * Affinity level (docs 20 5.4, proposed values: 0 stranger / 100 acquaintance / 300 friend / 600
 * best friend / 1,000 trusted).
 *
 * @param level level number (0-based)
 * @param points cumulative affinity required for this level
 * @param discountPercent shop discount % (read by Economy)
 * @param unlocks unlock tags (dialog choices, hidden quests, later parts of regional stories, etc.
 *     Other modules define their meaning)
 */
public record AffinityLevel(
        int level, int points, String name, int discountPercent, List<String> unlocks) {

    public AffinityLevel {
        unlocks = List.copyOf(unlocks);
    }
}
