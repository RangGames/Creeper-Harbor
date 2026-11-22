package wiki.creeper.harbor.world.api;

import java.util.Collection;
import java.util.Optional;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Harbor airship rides (docs 20 8.4 'rides'): happy ghast flights between the airship stations of
 * regions. Riders cannot dismount in flight and server moves are refused until they land. Main
 * thread only.
 */
public interface RideService {

    /** Result of {@link #board}. */
    enum BoardResult {
        /** Waiting for the next departure (every balance.yml world.airship interval). */
        QUEUED,
        /** Not within an airship station's radius. */
        NOT_AT_STOP,
        /** No route from this station (no other visited, unlocked station in the same world). */
        NO_ROUTE,
        /** The destination is not one of this station's routes for the player. */
        LOCKED
    }

    /**
     * Queues the player for the next departure to that region, the same as /비행선 &lt;region&gt;
     * (must stand at a station; the route needs a visit by ship and the region's entry conditions).
     * The player gets the usual notices.
     */
    BoardResult board(Player player, RegionId destination);

    /**
     * Admin: departs right now from the airship station of one region to the station of another
     * (same world), skipping the queue, visits and entry conditions. Riders are moved to the
     * departure station first; riders in another world or already flying are skipped. Several
     * airships are used if there are more riders than one holds.
     *
     * @return false if either region has no airship station, they are in different worlds, or no
     *     rider could board
     */
    boolean launch(Collection<Player> riders, RegionId from, RegionId to);

    /** Whether the player is on an airship in flight. */
    boolean riding(Player player);

    /** Whether the player is waiting for a departure. */
    boolean queued(Player player);

    /** Destination region of the airship the player is on. */
    Optional<RegionId> destination(Player player);

    /**
     * Takes the player out of the queue, or off an airship in flight (moved to the arrival point).
     *
     * @return false if the player was neither queued nor flying
     */
    boolean stop(Player player);
}
