package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.world.api.ShipInfo;

/** 선박을 띄우기 직전 (취소하면 띄우지 않는다). */
public final class ShipSummonEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ShipInfo ship;
    private boolean cancelled;

    public ShipSummonEvent(Player player, ShipInfo ship) {
        super(player);
        this.ship = ship;
    }

    public ShipInfo ship() {
        return ship;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
