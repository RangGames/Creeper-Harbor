package wiki.creeper.harbor.economy.api;

import java.util.concurrent.CompletableFuture;
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

    /**
     * Crafts a recipe for the player, the same flow as picking it in the recipe screen without the
     * confirmation dialog: unlock check → take the materials from the inventory and hold them → one
     * transaction (weekly limit, CRAFT_COST, unique fish ledger DESTROYED) → result item (mailbox
     * on overflow) and ItemCraftedEvent; on failure the materials are returned. Does not require
     * standing at a station. The future completes on the main thread.
     */
    CompletableFuture<TradeResult> craft(Player player, HarborId recipe);
}
