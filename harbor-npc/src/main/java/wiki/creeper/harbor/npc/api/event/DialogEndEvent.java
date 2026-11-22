package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.DialogEndReason;

/** Dialog session ended (closed, handed off to another screen, timed out, quit). */
public final class DialogEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final NpcId npc;
    private final HarborId dialog;
    private final String lastNode;
    private final DialogEndReason reason;

    public DialogEndEvent(
            Player player, NpcId npc, HarborId dialog, String lastNode, DialogEndReason reason) {
        this.player = player;
        this.npc = npc;
        this.dialog = dialog;
        this.lastNode = lastNode;
        this.reason = reason;
    }

    public Player player() {
        return player;
    }

    public NpcId npc() {
        return npc;
    }

    public HarborId dialog() {
        return dialog;
    }

    public String lastNode() {
        return lastNode;
    }

    public DialogEndReason reason() {
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
