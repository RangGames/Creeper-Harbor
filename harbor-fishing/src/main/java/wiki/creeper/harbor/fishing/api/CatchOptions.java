package wiki.creeper.harbor.fishing.api;

/**
 * Settings for {@link FishingService#createCatch} (Boss guaranteed catch of a boss species).
 *
 * @param qualityBonusStars stars added to the rolled quality (boss fight quality bonus). Never
 *     exceeds the job cap
 * @param extraLuck value added to the size roll luck
 * @param fireCaughtEvent whether to fire FishCaughtEvent (source BOSS) (Collection entries, Quest
 *     objectives)
 */
public record CatchOptions(int qualityBonusStars, double extraLuck, boolean fireCaughtEvent) {

    public static final CatchOptions BOSS = new CatchOptions(1, 0, true);

    public CatchOptions {
        if (qualityBonusStars < 0) {
            throw new IllegalArgumentException("qualityBonusStars >= 0");
        }
    }
}
