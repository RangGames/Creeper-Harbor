package wiki.creeper.harbor.npc.api;

/**
 * 호감도 변경 결과.
 *
 * @param applied 반영했는지 (접속 중이 아니면 false)
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
