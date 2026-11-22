package wiki.creeper.harbor.fishing.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.ItemId;

/**
 * Traps (docs 20 3.4 TrapService, "12. World, housing, traps" chapter 5). Players place, open and
 * harvest via the trap block; other modules use only this query/expansion API. Call on the main
 * thread; futures complete on the main thread.
 */
public interface TrapService {

    /** The player's traps (this season). */
    CompletableFuture<List<TrapInfo>> traps(UUID owner);

    /**
     * Expands a trap by one slot (price = balance.yml fishing.trap.slot_price per slot × trap
     * tier). The gold deduction (ledger TRAP_SLOT_EXPAND) and the traps update run in one
     * transaction. The Economy shop screen calls this method to wire up payment.
     */
    CompletableFuture<SlotPurchase> purchaseSlot(Player owner, long trapId);

    /** True if it is a trap catch (trap source in custom_data, for Economy auction monitoring). */
    boolean isTrapCatch(ItemStack stack);

    /** One trap by ID (any owner, any season). Empty if it does not exist. */
    CompletableFuture<Optional<TrapInfo>> trap(long trapId);

    /** ID of the trap whose barrel block is at that location (main thread, no DB). */
    OptionalLong trapIdAt(Location location);

    /**
     * Places a trap for the player near the buoy spot closest to the location, with the same checks
     * as placing it by hand (region trap zone, buoy radius, free spot, deep trap skill, trap
     * count). With takeItem, one trap item is taken from the inventory first and returned on
     * failure; without it the trap is free (admin). The player gets the same chat messages as by
     * hand.
     */
    CompletableFuture<TrapAction> place(
            Player owner, Location location, ItemId trapItem, boolean takeItem);

    /**
     * Harvests everything the trap holds, like opening it and pressing "harvest all": the offline
     * calculation runs first, then as many catches as there are free inventory slots are taken out
     * (items created after the DB commit, sent by mail during a server move). Fires
     * TrapHarvestEvent.
     */
    CompletableFuture<TrapAction> harvest(Player owner, long trapId);

    /**
     * Puts bait from the owner's inventory into the trap (same rules as the trap screen: no
     * legendary bait or bait with uses, up to the bait capacity).
     */
    CompletableFuture<TrapAction> addBait(Player owner, long trapId);

    /**
     * Removes an empty trap and returns the trap item and remaining bait to the owner (same as the
     * trap screen's remove button). NOT_EMPTY if catches are stored.
     */
    CompletableFuture<TrapAction> remove(Player owner, long trapId);

    /**
     * Admin removal (like {@code /harbor fishing trap remove}): contents and bait are discarded.
     */
    CompletableFuture<Boolean> adminRemove(long trapId);

    /** Slot expansion result. */
    enum SlotPurchase {
        OK,
        NOT_FOUND,
        NOT_OWNER,
        MAX_REACHED,
        NO_GOLD,
        FAILED
    }
}
