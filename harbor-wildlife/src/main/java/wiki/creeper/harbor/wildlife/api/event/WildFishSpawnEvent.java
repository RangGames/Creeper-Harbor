package wiki.creeper.harbor.wildlife.api.event;

import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.wildlife.api.Medium;

/** Fired just before a wild fish (or school) is created (main thread). Cancelling prevents it. */
public final class WildFishSpawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why it spawns. */
    public enum Reason {
        /** Natural spawn around a player. */
        NATURAL,
        /** Fish school spot (World FishingSpotMovedEvent). */
        SPOT,
        /** spawnScripted (events, cinematics, admin commands). */
        SCRIPTED,
        /** spawnSchool (school following a path). */
        PATH
    }

    private final FishId fish;
    private final Location location;
    private final int count;
    private final Medium medium;
    private final Reason reason;
    private boolean cancelled;

    public WildFishSpawnEvent(
            FishId fish, Location location, int count, Medium medium, Reason reason) {
        this.fish = fish;
        this.location = location.clone();
        this.count = count;
        this.medium = medium;
        this.reason = reason;
    }

    public FishId fish() {
        return fish;
    }

    public Location location() {
        return location.clone();
    }

    /** Number of fish in the school (1 means solitary). */
    public int count() {
        return count;
    }

    public Medium medium() {
        return medium;
    }

    public Reason reason() {
        return reason;
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
