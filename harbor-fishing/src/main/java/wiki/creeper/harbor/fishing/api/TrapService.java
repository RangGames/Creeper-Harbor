package wiki.creeper.harbor.fishing.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
