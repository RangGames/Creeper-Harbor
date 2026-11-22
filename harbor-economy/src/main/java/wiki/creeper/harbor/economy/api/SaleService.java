package wiki.creeper.harbor.economy.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Fish selling for a player (docs 20 7.4 sell flow), the same flow as /판매 without the confirmation
 * dialog: quote with bulk-sale slippage → take the fish and hold them → one transaction (FISH_SELL
 * gold + fish ledger SOLD) → market sales volume and FishSoldEvent; on failure the fish are
 * returned (mailbox if the inventory is full). Call on the main thread; the future completes on the
 * main thread.
 */
public interface SaleService {

    /** Sells the held stack (valuable fish included, like /판매 손). */
    CompletableFuture<TradeResult> sellHeld(Player player);

    /**
     * Sells fish in the inventory, skipping valuable fish (locked, unique number, ★5) like /판매 전부.
     *
     * @param species only this species (null = every species)
     */
    CompletableFuture<TradeResult> sellInventory(Player player, @Nullable FishId species);

    /**
     * Sells the fish placed in the player's open sell chest (/판매). Valuable fish stay in the chest.
     * NOTHING if no sell chest is open.
     */
    CompletableFuture<TradeResult> sellBox(Player player);
}
