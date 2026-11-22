package wiki.creeper.harbor.economy.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;

/**
 * After a shop purchase is committed. For a vanilla material entry, item is null and key is
 * vanilla/<key>.
 */
public final class ShopPurchaseEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final HarborId shop;
    private final @Nullable ItemId item;
    private final String key;
    private final int amount;
    private final long price;

    public ShopPurchaseEvent(
            UUID player, HarborId shop, @Nullable ItemId item, String key, int amount, long price) {
        this.player = player;
        this.shop = shop;
        this.item = item;
        this.key = key;
        this.amount = amount;
        this.price = price;
    }

    public UUID player() {
        return player;
    }

    public HarborId shop() {
        return shop;
    }

    public @Nullable ItemId item() {
        return item;
    }

    /** Shop entry key (item path or vanilla/<key>). */
    public String key() {
        return key;
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
