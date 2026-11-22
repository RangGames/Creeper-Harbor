package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Harbor aquarium (server-wide goal, docs 20 4.4). Call on the main thread; results also complete
 * on the main thread.
 */
public interface AquariumService {

    /**
     * State. rate is distinct species donated this season ÷ donatable species (excluding mythic and
     * release_only).
     */
    record Status(long points, int species, int donatable, double rate, int stage) {}

    /**
     * Result of a multi-fish donation.
     *
     * @param donated number of fish donated
     * @param points total points earned
     * @param returned number of items returned (not fish, not donatable, or failed donations)
     * @param failed whether the transaction failed and everything was returned
     */
    record DonationResult(int donated, int points, int returned, boolean failed) {

        public static final DonationResult NONE = new DonationResult(0, 0, 0, false);

        public boolean ok() {
            return donated > 0 && !failed;
        }
    }

    Status status();

    /** Donates the fish item in hand (after the confirmation screen). */
    CompletableFuture<Boolean> donateHeld(Player player);

    /**
     * Opens the donation box (chest GUI) (Iseul dialog action aquarium:donate, aquarium reception
     * desk). On close: confirmation screen → donation.
     */
    void openDonationBox(Player player);

    /**
     * Donates items already taken out of the inventory in one transaction without a confirmation
     * screen (Quest collection request donate_delivered). Ownership of the passed items moves to
     * this service: non-fish or undonatable items and failed donations are returned to the player,
     * and if the inventory is full or the player disconnected, they go to the mailbox (MailService)
     * instead.
     *
     * <p>Unique-ID items go ACTIVE → DONATED in the ledger. If source starts with {@code quest:},
     * it also accepts items Quest already changed to DONATED before this transaction.
     *
     * @param source source (e.g. {@code quest:<quest ID>}, {@code npc}, {@code box})
     */
    CompletableFuture<DonationResult> donateItems(
            Player player, List<ItemStack> items, String source);
}
