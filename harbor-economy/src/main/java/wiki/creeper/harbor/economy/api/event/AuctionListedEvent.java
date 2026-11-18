package wiki.creeper.harbor.economy.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 경매 등록이 커밋된 뒤 (메인 스레드). */
public final class AuctionListedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID seller;
    private final long listingId;
    private final String itemId;
    private final int amount;
    private final long price;
    private final long fee;

    public AuctionListedEvent(
            UUID seller, long listingId, String itemId, int amount, long price, long fee) {
        this.seller = seller;
        this.listingId = listingId;
        this.itemId = itemId;
        this.amount = amount;
        this.price = price;
        this.fee = fee;
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

    public int amount() {
        return amount;
    }

    public long price() {
        return price;
    }

    public long fee() {
        return fee;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
