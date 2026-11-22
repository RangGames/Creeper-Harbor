package wiki.creeper.harbor.core.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/** A player got off a furniture seat (main thread, proposed). Not cancellable. */
public final class FurnitureStandEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final @Nullable HarborId furniture;

    public FurnitureStandEvent(Player player, Location location, @Nullable HarborId furniture) {
        this.player = player;
        this.location = location;
        this.furniture = furniture;
    }

    public Player player() {
        return player;
    }

    /** Seat location. */
    public Location location() {
        return location.clone();
    }

    /** Furniture ID (null if the furniture is already gone). */
    public @Nullable HarborId furniture() {
        return furniture;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
