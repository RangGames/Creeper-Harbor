package wiki.creeper.harbor.economy.service;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.economy.api.SaleService;
import wiki.creeper.harbor.economy.api.TradeResult;

/** {@link SaleService}: delegates to the /판매 flow ({@link SellService}) and the sell chest. */
public final class SaleApi implements SaleService {

    private final SellService sell;
    private final SellMenu menu;

    public SaleApi(SellService sell, SellMenu menu) {
        this.sell = sell;
        this.menu = menu;
    }

    @Override
    public CompletableFuture<TradeResult> sellHeld(Player player) {
        return sell.sellNow(player, true, null);
    }

    @Override
    public CompletableFuture<TradeResult> sellInventory(Player player, @Nullable FishId species) {
        return sell.sellNow(player, false, species);
    }

    @Override
    public CompletableFuture<TradeResult> sellBox(Player player) {
        return menu.sellNow(player);
    }
}
