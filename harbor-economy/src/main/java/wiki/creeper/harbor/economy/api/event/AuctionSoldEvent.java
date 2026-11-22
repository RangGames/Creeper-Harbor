package wiki.creeper.harbor.economy.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * After an auction sale is committed (main thread of the buyer's server). If held, the sale
 * proceeds were withheld.
 */
public final class AuctionSoldEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID buyer;
    private final UUID seller;
    private final long listingId;
    private final String itemId;
    private final long price;
    private final long fee;
    private final boolean held;

    public AuctionSoldEvent(
            UUID buyer,
            UUID seller,
            long listingId,
            String itemId,
            long price,
            long fee,
            boolean held) {
        this.buyer = buyer;
        this.seller = seller;
        this.listingId = listingId;
        this.itemId = itemId;
        this.price = price;
        this.fee = fee;
        this.held = held;
    }

    public UUID buyer() {
        return buyer;
    }

    public UUID seller() {
        return seller;
    }

    public long listingId() {
        return listingId;
    }

    public String itemId() {
        return itemId;
    }

    public long price() {
        return price;
    }

    public long fee() {
        return fee;
    }

    public boolean held() {
        return held;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
