package wiki.creeper.harbor.core.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Per-player world border arbitration (docs 20 0.2). So modules can share the single per-player
 * world border, requests are kept by priority and only the highest one is shown. Removing a request
 * falls back to the next-priority one; with no requests left the personal border is removed. Main
 * thread only. Everything is cleared on quit.
 */
public interface WorldBorderService {

    /** Highest first: boss arena > Fishing tension warning > World environment warning. */
    enum Priority {
        WORLD_ENVIRONMENT,
        FISHING_TENSION,
        BOSS_ARENA
    }

    /**
     * @param center border center
     * @param size side length (blocks)
     * @param warningDistance distance at which the screen starts turning red. Make it large for a
     *     warning-only effect
     */
    record Border(Location center, double size, int warningDistance) {

        public Border {
            center = center.clone();
            if (size <= 0) {
                throw new IllegalArgumentException("size > 0");
            }
        }

        /**
         * Warning effect that keeps the border far away and only tints the screen red (for tension
         * and environment warnings).
         */
        public static Border warning(Location at) {
            return new Border(at, 1_000_000, Integer.MAX_VALUE);
        }
    }

    void request(Player player, Priority priority, Border border);

    void clear(Player player, Priority priority);
}
