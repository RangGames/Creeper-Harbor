package wiki.creeper.harbor.core.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.core.api.RigInstance;

/**
 * A rig animation event ({@code events} of an animation in rigs/*.yml) as a Bukkit event (main
 * thread, proposed). Only fired for rigs that asked for it with {@link
 * RigInstance#callBukkitEvents()} (Harbor-Skript does this for rigs spawned "with events"); module
 * code listens with {@link RigInstance#onEvent} directly.
 */
public final class RigAnimationEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RigInstance rig;
    private final String animation;
    private final String name;

    public RigAnimationEvent(RigInstance rig, String animation, String name) {
        this.rig = rig;
        this.animation = animation;
        this.name = name;
    }

    public RigInstance rig() {
        return rig;
    }

    /** Animation that emitted the event. */
    public String animation() {
        return animation;
    }

    /** Event name from the animation's events list. */
    public String name() {
        return name;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
