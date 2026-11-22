package wiki.creeper.harbor.economy.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Crafting (docs 20 7.2). Station clicks, the craft:open dialog action and admin commands open this
 * screen. Main thread.
 */
public interface CraftService {

    /**
     * Opens a station (stations.yml) screen. False if the station does not exist or is not a
     * crafting place.
     */
    boolean openStation(Player player, HarborId station);

    /**
     * Opens the recipe screen for a place kind (workshop · cooking · shipyard). False for an
     * unknown kind.
     */
    boolean openKind(Player player, String kind);
}
