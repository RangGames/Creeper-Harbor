package wiki.creeper.harbor.npc.api;

/**
 * Result of an affinity change.
 *
 * @param applied whether it was applied (false if the player is not online)
 */
public record AffinityChange(
        boolean applied, int oldPoints, int newPoints, int oldLevel, int newLevel) {

    public static AffinityChange rejected(int points, int level) {
        return new AffinityChange(false, points, points, level, level);
    }

    public boolean levelUp() {
        return newLevel > oldLevel;
    }
}
