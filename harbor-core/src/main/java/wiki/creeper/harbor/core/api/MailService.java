package wiki.creeper.harbor.core.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;

/**
 * Mailbox (docs 20 0.2, 7.4). The interface lives in Core and Economy registers the implementation.
 * Fishing (automatic trap collection), Quest (reward returns) and Collection (failed donation
 * returns) depend only on Core, so no circular dependency with Economy arises. If there is no
 * implementation (if {@link HarborApi#mail()} is empty), the caller defers. Safe to call from any
 * thread; results complete after the DB commit.
 */
public interface MailService {

    CompletableFuture<Void> sendItem(
            UUID player, ItemStack item, String source, @Nullable String refId);

    /** Gold mail: granted to the ledger as MAIL_GOLD when claimed. */
    CompletableFuture<Void> sendGold(UUID player, long gold, String source, @Nullable String refId);

    /** Number of unclaimed mails. */
    CompletableFuture<Integer> unclaimed(UUID player);

    /**
     * Inserts item mail inside the caller's transaction (proposed). Committed together with other
     * DB changes (row deletion, etc.) so that a server crash in between can't lose the item or
     * deliver it twice. Does nothing if the same (recipient, source, refId) already exists. Items
     * are serialized at call time (call on the main thread). After committing, call {@link
     * #notifyArrived}.
     */
    SqlConsumer sendItemInTx(UUID player, ItemStack item, String source, String refId);

    /**
     * Notifies the recipient that mail has arrived (including on other servers). Call after
     * committing {@link #sendItemInTx}.
     */
    void notifyArrived(UUID player);
}
