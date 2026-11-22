package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * NPC clicked (after distance and state checks, before opening the dialog). Cancelling prevents the
 * dialog from opening. Used by Quest to handle turn-ins first.
 */
public final class NpcInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final NpcId npc;
    private final boolean rightClick;
    private boolean cancelled;

    public NpcInteractEvent(Player player, NpcId npc, boolean rightClick) {
        this.player = player;
        this.npc = npc;
        this.rightClick = rightClick;
    }

    public Player player() {
        return player;
    }

    public NpcId npc() {
        return npc;
    }

    public boolean rightClick() {
        return rightClick;
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
