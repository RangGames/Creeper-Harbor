package wiki.creeper.harbor.core.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Right before furniture is placed (main thread, proposed), after the space and protection checks.
 * Fired for player placement and for {@link wiki.creeper.harbor.core.api.FurnitureService#place}
 * (then {@link #player()} is the acting player, or null for a plain API placement). Cancelling
 * places nothing (a player keeps the item).
 */
public final class FurniturePlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable Player player;
    private final Location location;
    private final HarborId furniture;
    private final float yaw;
    private boolean cancelled;

    public FurniturePlaceEvent(
            @Nullable Player player, Location location, HarborId furniture, float yaw) {
        this.player = player;
        this.location = location;
        this.furniture = furniture;
        this.yaw = yaw;
    }

    /** Placing player (null for an API placement without one). */
    public @Nullable Player player() {
        return player;
    }

    /** Center of the floor of the block the furniture will sit on. */
    public Location location() {
        return location.clone();
    }

    public HarborId furniture() {
        return furniture;
    }

    /** Facing after snapping to the furniture's rotation step. */
    public float yaw() {
        return yaw;
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
