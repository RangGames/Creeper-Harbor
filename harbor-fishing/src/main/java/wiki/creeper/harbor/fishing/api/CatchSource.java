package wiki.creeper.harbor.fishing.api;

/** Where a fish item came from (FishCaughtEvent, custom_data for trap monitoring). */
public enum CatchSource {
    /** Rod minigame. */
    ROD,
    /** Trap harvest. */
    TRAP,
    /** Guaranteed boss fight catch ({@link FishingService#createCatch}). */
    BOSS
}
