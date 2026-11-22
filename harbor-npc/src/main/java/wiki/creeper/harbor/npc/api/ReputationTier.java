package wiki.creeper.harbor.npc.api;

/**
 * Reputation tier (docs 20 5.4, proposed values: 1 outsider 0 / 2 neighbor 300 / 3 regular 1,000 /
 * 4 harbor member 2,000 / 5 pillar of the harbor 3,500).
 *
 * @param tier 1-based
 * @param points cumulative reputation required
 */
public record ReputationTier(int tier, int points, String name) {}
