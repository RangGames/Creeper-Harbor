package wiki.creeper.harbor.core.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.core.api.RigHit;
import wiki.creeper.harbor.core.api.RigInstance;

/**
 * A click on a rig part hitbox as a Bukkit event (main thread, proposed). Only fired for rigs
 * spawned with {@link wiki.creeper.harbor.core.api.RigOptions#withHitboxEvents()} (Harbor-Skript
 * "with events"); module code passes its own handler to {@link
 * wiki.creeper.harbor.core.api.RigOptions#withHitboxes}. The original click is already cancelled.
 */
public final class RigHitboxClickEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RigHit hit;

    public RigHitboxClickEvent(RigHit hit) {
        this.hit = hit;
    }

    public Player player() {
        return hit.player();
    }

    public RigInstance rig() {
        return hit.rig();
    }

    /** Name of the clicked bone (rigs/*.yml bones[].name). */
    public String bone() {
        return hit.bone();
    }

    /** True for left click (attack), false for right click. */
    public boolean attack() {
        return hit.attack();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
