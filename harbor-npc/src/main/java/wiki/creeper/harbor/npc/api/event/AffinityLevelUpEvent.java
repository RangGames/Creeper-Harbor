package wiki.creeper.harbor.npc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.NpcId;

/** 호감도 단계 상승. */
public final class AffinityLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final NpcId npc;
    private final int oldLevel;
    private final int newLevel;

    public AffinityLevelUpEvent(UUID player, NpcId npc, int oldLevel, int newLevel) {
        this.player = player;
        this.npc = npc;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public UUID player() {
        return player;
    }

    public NpcId npc() {
        return npc;
    }

    public int oldLevel() {
        return oldLevel;
    }

    public int newLevel() {
        return newLevel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
