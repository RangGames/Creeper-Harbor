package wiki.creeper.harbor.economy.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;

/** 상점 구매가 커밋된 뒤. */
public final class ShopPurchaseEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final HarborId shop;
    private final ItemId item;
    private final int amount;
    private final long price;

    public ShopPurchaseEvent(UUID player, HarborId shop, ItemId item, int amount, long price) {
        this.player = player;
        this.shop = shop;
        this.item = item;
        this.amount = amount;
        this.price = price;
    }

    public UUID player() {
        return player;
    }

    public HarborId shop() {
        return shop;
    }

    public ItemId item() {
        return item;
    }

    public int amount() {
        return amount;
    }

    public long price() {
        return price;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
