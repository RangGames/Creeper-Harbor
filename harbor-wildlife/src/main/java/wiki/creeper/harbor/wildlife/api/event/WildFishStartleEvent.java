package wiki.creeper.harbor.wildlife.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.wildlife.api.WildFish;

/**
 * A fish was startled by a player and fled (decided on the simulation thread, announced on the main
 * thread). Only the first startled fish fires this, not each fish the startle spreads to in the
 * school.
 */
public final class WildFishStartleEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WildFish fish;

    public WildFishStartleEvent(WildFish fish, Player player) {
        super(player);
        this.fish = fish;
    }

    public WildFish fish() {
        return fish;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
