package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.world.api.ShipInfo;

/** 선박 내구도가 0이 되어 파손됐을 때. player는 주인. */
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
