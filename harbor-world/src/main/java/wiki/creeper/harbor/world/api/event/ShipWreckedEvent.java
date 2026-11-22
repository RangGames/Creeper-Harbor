package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.world.api.ShipInfo;

/** When a ship's durability reached 0 and it was wrecked. player is the owner. */
public final class ShipWreckedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ShipInfo ship;

    public ShipWreckedEvent(Player player, ShipInfo ship) {
        super(player);
        this.ship = ship;
    }

    public ShipInfo ship() {
        return ship;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
