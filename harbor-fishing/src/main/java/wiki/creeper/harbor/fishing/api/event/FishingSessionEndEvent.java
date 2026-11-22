package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;

/**
 * A cast that ended before reaching the minigame (proposed, docs 20 3.2). Fired when it ends by
 * reeling early, missing the bite or switching bait. For sessions that ended through the minigame
 * see {@link MinigameEndEvent}. Wildlife may release the approaching individual on this event
 * (ShadowDriver.release is also called separately).
 */
public final class FishingSessionEndEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why it ended. */
    public enum Reason {
        REELED_EARLY,
        MISSED_BITE,
        BAIT_CHANGED,
        INTERRUPTED
    }

    private final FishDef fish;
    private final Reason reason;

    public FishingSessionEndEvent(Player player, FishDef fish, Reason reason) {
        super(player);
        this.fish = fish;
        this.reason = reason;
    }

    public FishDef fish() {
        return fish;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
