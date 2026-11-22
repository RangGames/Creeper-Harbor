package wiki.creeper.harbor.core.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Right before a player picks up furniture (main thread, proposed). Cancelling leaves it in place.
 * With {@link #setReturnItem(boolean) setReturnItem(false)} the item is not returned (when the
 * listener stores it separately, like housing decor).
 *
 * <p>{@link #allowed()}: true from the start for the player who placed it or with the
 * harbor.furniture.manage permission. Otherwise a listener must allow it (housing: plot build
 * permission). If it stays false, the furniture is not picked up.
 */
public final class FurnitureRemoveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final HarborId furniture;
    private final ItemStack item;
    private boolean returnItem = true;
    private boolean allowed;
    private boolean cancelled;

    public FurnitureRemoveEvent(
            Player player, Location location, HarborId furniture, ItemStack item, boolean allowed) {
        this.player = player;
        this.allowed = allowed;
        this.location = location;
        this.furniture = furniture;
        this.item = item;
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

    /** The item used to place it (1, a copy). */
    public ItemStack item() {
        return item.clone();
    }

    public boolean allowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean returnItem() {
        return returnItem;
    }

    public void setReturnItem(boolean returnItem) {
        this.returnItem = returnItem;
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
