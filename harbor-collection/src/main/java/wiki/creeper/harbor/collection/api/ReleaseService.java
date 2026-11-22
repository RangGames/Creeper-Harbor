package wiki.creeper.harbor.collection.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Release (docs 20 4.4, Phase 3). Opens once server flag climax_done is set. It replaces selling,
 * so it gives no gold; for rare and above it gives reputation (NPC ReputationService, source
 * RELEASE, daily cap) and a luck buff (size_luck). Unique-ID items have their ledger changed to
 * RELEASED.
 */
public interface ReleaseService {

    /** Result. */
    enum Result {
        RELEASED,
        /** Release is not open yet (before climax_done). */
        LOCKED,
        /** Not a fish item, or not releasable (e.g. release_only). */
        NOT_ALLOWED,
        /** Cancelled on the confirmation screen. */
        CANCELLED,
        /** Ledger state mismatch or save failure (the item is returned). */
        FAILED
    }

    boolean available();

    /**
     * Releases one fish item from the player's inventory (after the confirmation screen). The
     * result completes on the main thread.
     *
     * @param stack item in the inventory (one fish is removed)
     */
    CompletableFuture<Result> release(Player player, ItemStack stack);
}
