package wiki.creeper.harbor.shop.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.donor.DonorTier;

/**
 * After an online player's donor tier changes (main thread). For refreshing name color, chat icon
 * and aquarium theme slots.
 */
public final class DonorTierChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final DonorTier previous;
    private final DonorTier current;

    public DonorTierChangedEvent(UUID player, DonorTier previous, DonorTier current) {
        this.player = player;
        this.previous = previous;
        this.current = current;
    }

    public UUID player() {
        return player;
    }

    public DonorTier previous() {
        return previous;
    }

    public DonorTier current() {
        return current;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
