package wiki.creeper.harbor.npc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.npc.api.ReputationSource;

/** Reputation changed (Economy auction slots, World housing, Collection aquarium slots). */
public final class ReputationChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int oldValue;
    private final int newValue;
    private final ReputationSource source;
    private final @Nullable String refId;

    public ReputationChangedEvent(
            UUID player,
            int oldValue,
            int newValue,
            ReputationSource source,
            @Nullable String refId) {
        this.player = player;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.source = source;
        this.refId = refId;
    }

    public UUID player() {
        return player;
    }

    public int oldValue() {
        return oldValue;
    }

    public int newValue() {
        return newValue;
    }

    public ReputationSource source() {
        return source;
    }

    public @Nullable String refId() {
        return refId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
