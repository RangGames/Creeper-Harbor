package wiki.creeper.harbor.core.api;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Locator bar waypoints without a datapack ("4. Fishing presentation" 1.3: fish schools, treasure,
 * boss direction, proposed). Paper has no waypoint API, so each target is a hidden, invisible
 * marker entity with the waypoint_transmit_range attribute shown only to its viewer; color and icon
 * style are set with the vanilla /waypoint command. Callers declare the full set of targets per
 * (viewer, owner) and the service adds, moves and removes markers to match. Main thread only.
 */
public interface WaypointService {

    /**
     * One target.
     *
     * @param id stable ID within the owner (e.g. a spot ID)
     * @param at target location
     * @param color #RRGGBB icon color (null = default)
     * @param style waypoint style key in the resource pack (e.g. harbor:fish_school, null =
     *     default)
     * @param range transmit range in blocks (beyond it the icon disappears)
     */
    record Target(
            String id, Location at, @Nullable String color, @Nullable String style, double range) {}

    /** Replaces the owner's targets for this viewer (empty list = clear). */
    void set(Player viewer, String owner, List<Target> targets);

    /** Clears the owner's targets for this viewer. */
    default void clear(Player viewer, String owner) {
        set(viewer, owner, List.of());
    }

    /** Used when Core has not registered the service (tests, disabled feature). */
    WaypointService NOOP = (viewer, owner, targets) -> {};
}
