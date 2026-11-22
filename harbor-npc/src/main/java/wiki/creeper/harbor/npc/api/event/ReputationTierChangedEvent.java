package wiki.creeper.harbor.npc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Reputation tier changed. bestTier is the best tier reached after the change (kept across
 * seasons).
 */
public final class ReputationTierChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int oldTier;
    private final int newTier;
    private final int bestTier;

    public ReputationTierChangedEvent(UUID player, int oldTier, int newTier, int bestTier) {
        this.player = player;
        this.oldTier = oldTier;
        this.newTier = newTier;
        this.bestTier = bestTier;
    }

    public UUID player() {
        return player;
    }

    public int oldTier() {
        return oldTier;
    }

    public int newTier() {
        return newTier;
    }

    public int bestTier() {
        return bestTier;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
