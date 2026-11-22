package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Dialog choice selected (after re-checking conditions, before running actions). For stats and
 * tutorials.
 */
public final class DialogOptionSelectedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final NpcId npc;
    private final HarborId dialog;
    private final String node;
    private final int option;

    public DialogOptionSelectedEvent(
            Player player, NpcId npc, HarborId dialog, String node, int option) {
        this.player = player;
        this.npc = npc;
        this.dialog = dialog;
        this.node = node;
        this.option = option;
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

    public String node() {
        return node;
    }

    public int option() {
        return option;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
