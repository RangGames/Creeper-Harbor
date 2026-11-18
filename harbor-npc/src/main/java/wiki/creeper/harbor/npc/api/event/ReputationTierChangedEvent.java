package wiki.creeper.harbor.npc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 평판 등급 변경. bestTier는 변경 후 최고 도달 등급 (시즌 간 유지). */
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
