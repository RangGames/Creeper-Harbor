package wiki.creeper.harbor.core.api;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Where a module temporarily holds a player's items outside the inventory (sell box, donation box,
 * pending-confirmation list, etc., proposed). Autosaves and post-trade saves don't close open
 * windows, so when Core takes a snapshot it puts the items received here into the snapshot
 * inventory (overflowing into the ender chest). If the server crashes in between, the held items
 * are not lost and return to the inventory. Not called for quit, transfer or shutdown saves (the
 * module returns the items itself when closing the window or handling the quit).
 *
 * <p>Don't register items that are also recorded elsewhere, such as the DB (duplication). Don't
 * include items that are being processed (transaction in progress) either.
 */
@FunctionalInterface
public interface ItemCustody {

    /**
     * Main thread. Items currently held for this player. Used only for the snapshot, so return
     * copies.
     */
    List<ItemStack> held(Player player);
}
