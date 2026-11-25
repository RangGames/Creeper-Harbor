package wiki.creeper.harbor.fishing.api;

import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Shared tension-model session (docs 20 3.2 openTension, for the Boss anchor). Reuses the Fishing
 * minigame engine (20Hz, Shift reel + A/D direction, tension gauge and boss bar UI, screen edge
 * warning at tension 0.85 or above) as is, and only the result goes to Boss. Items, EXP and bait
 * are not handled. Main thread only.
 *
 * <p>Tension T is 0..1 (1 = line snaps), the health ratio goes 1 → 0. ESCAPED if the player leaves
 * or dies; ending with {@link #close} ends with that result.
 */
public interface TensionSession {

    UUID player();

    double tension();

    /** Opponent health ratio (1 = full). */
    double hpRatio();

    /** Phase determined by the health ratio, like the legendary 3 phases (starting at 1). */
    int phase();

    boolean isActive();

    /** Null while in progress. */
    @Nullable MinigameResult result();

    /**
     * The player lowered the rod (switched to another item): the line is held loosely, tension and
     * health do not change and the player can move. Raising the rod again resumes. Callers should
     * not count a paused line as holding (proposed).
     */
    default boolean paused() {
        return false;
    }

    /** Boss pattern: adds tension (negative lowers it). */
    void addTension(double delta);

    /** External damage such as harpoons: reduces health by a fraction of max health. */
    void damage(double fraction);

    /** Called once when finished (called immediately if already finished). */
    void onEnd(Consumer<MinigameResult> listener);

    /** Ends it externally (boss killed, fled, timed out). Ignored if already finished. */
    void close(MinigameResult result);
}
