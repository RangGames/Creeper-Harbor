package wiki.creeper.harbor.core.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * A player right-clicked placed furniture (main thread, proposed), before Core sits them down or
 * starts a pick-up. Cancelling skips both (scripts can give furniture their own use).
 */
public final class FurnitureInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Interaction entity;
    private final Location location;
    private final HarborId furniture;
    private final boolean pickup;
    private boolean cancelled;

    public FurnitureInteractEvent(
            Player player,
            Interaction entity,
            Location location,
            HarborId furniture,
            boolean pickup) {
        this.player = player;
        this.entity = entity;
        this.location = location;
        this.furniture = furniture;
        this.pickup = pickup;
    }

    public Player player() {
        return player;
    }

    /** The furniture's click hitbox. */
    public Interaction entity() {
        return entity;
    }

    /** Center of the floor of the block the furniture sits on. */
    public Location location() {
        return location.clone();
    }

    public HarborId furniture() {
        return furniture;
    }

    /** True for sneak + right-click (a pick-up attempt follows), false for a plain click. */
    public boolean pickup() {
        return pickup;
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
