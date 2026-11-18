package wiki.creeper.harbor.npc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;

/** 컷씬 시작 (준비가 끝나고 재생 직전). */
public final class CutsceneStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final HarborId cutscene;

    public CutsceneStartEvent(Player player, HarborId cutscene) {
        this.player = player;
        this.cutscene = cutscene;
    }

    public Player player() {
        return player;
    }

    public HarborId cutscene() {
        return cutscene;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
