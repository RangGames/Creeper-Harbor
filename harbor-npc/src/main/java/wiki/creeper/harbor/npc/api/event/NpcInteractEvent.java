package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.NpcId;

/** NPC 클릭 (거리·상태 검사 뒤, 대화를 열기 전). 취소하면 대화를 열지 않는다. Quest가 완료 보고를 우선 처리할 때 쓴다. */
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
