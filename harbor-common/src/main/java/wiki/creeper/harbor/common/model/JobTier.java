package wiki.creeper.harbor.common.model;

/** Job tier (1st, 2nd, 3rd). */
public enum JobTier {
    FIRST(1),
    SECOND(2),
    THIRD(3);

    private final int level;

    JobTier(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public static JobTier of(int level) {
        for (JobTier tier : values()) {
            if (tier.level == level) {
                return tier;
            }
        }
        throw new IllegalArgumentException("직업 단계는 1~3 사이여야 합니다: " + level);
    }
}
