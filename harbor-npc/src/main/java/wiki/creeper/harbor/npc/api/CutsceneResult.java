package wiki.creeper.harbor.npc.api;

/** Cutscene result (docs 20 5.2). Quest does not advance the stage on CANCELLED or BUSY. */
public enum CutsceneResult {
    FINISHED,
    /** An already-seen cutscene skipped by sneaking twice. */
    SKIPPED,
    /** Quit, admin stop, error, or missing cutscene. */
    CANCELLED,
    /** Not started because of a minigame, boss fight or another cutscene. */
    BUSY;

    /** Whether it was watched to the end or skipped (OK to move on to the next stage). */
    public boolean completed() {
        return this == FINISHED || this == SKIPPED;
    }
}
