package wiki.creeper.harbor.npc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.AffinitySource;

/** Affinity changed (Quest condition re-evaluation, Economy discount refresh). */
public final class AffinityChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final NpcId npc;
    private final int oldPoints;
    private final int newPoints;
    private final AffinitySource source;

    public AffinityChangedEvent(
            UUID player, NpcId npc, int oldPoints, int newPoints, AffinitySource source) {
        this.player = player;
        this.npc = npc;
        this.oldPoints = oldPoints;
        this.newPoints = newPoints;
        this.source = source;
    }

    public UUID player() {
        return player;
    }

    public NpcId npc() {
        return npc;
    }

    public int oldPoints() {
        return oldPoints;
    }

    public int newPoints() {
        return newPoints;
    }

    public AffinitySource source() {
        return source;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
