package wiki.creeper.harbor.core.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Right before a player sits on seatable furniture (main thread, proposed). Cancelling keeps them
 * standing.
 */
public final class FurnitureSitEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final HarborId furniture;
    private boolean cancelled;

    public FurnitureSitEvent(Player player, Location location, HarborId furniture) {
        this.player = player;
        this.location = location;
        this.furniture = furniture;
    }

    public Player player() {
        return player;
    }

    /** Center of the floor of the block the furniture sits on. */
    public Location location() {
        return location.clone();
    }

    public HarborId furniture() {
        return furniture;
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
