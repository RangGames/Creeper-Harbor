package wiki.creeper.harbor.economy.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;

/** NPC shops (NPC clicks; World ship and decoration purchases use this too). Main thread. */
public interface ShopService {

    /** Opens the shop screen. False if the shop does not exist. */
    boolean open(Player player, HarborId shop);

    /**
     * Buys a shop entry for the player, the same flow as clicking it in the shop screen: unlock
     * check, price with the shop NPC's affinity discount and shop_buy_price, SHOP_BUY ledger and
     * daily limit in one transaction, then the item (mailbox on overflow) and ShopPurchaseEvent.
     * Needs one free inventory slot. The future completes on the main thread.
     *
     * @param entryKey entry key in shops.yml
     * @param amount clamped to 1 .. one stack
     */
    CompletableFuture<TradeResult> buy(Player player, HarborId shop, String entryKey, int amount);

    /**
     * Gold payment for other modules (World housing decor HOUSING_DECOR, Fishing trap slot
     * expansion TRAP_SLOT_EXPAND …). Sets the in-trade marker to defer server moves and accepts
     * only one at a time (REJECTED if one is in progress). extra is SQL to run in the same
     * transaction (number of decorations bought, etc.). The future completes on the main thread.
     *
     * @return balance after processing
     */
    CompletableFuture<Result<Long, LedgerError>> charge(
            Player player,
            long price,
            LedgerReason reason,
            String refId,
            @Nullable SqlConsumer extra);
}
