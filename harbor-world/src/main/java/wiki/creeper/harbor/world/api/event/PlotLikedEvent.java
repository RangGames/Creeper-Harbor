package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.world.api.PlotInfo;

/** 방문객이 좋아요를 눌렀을 때 (커밋 후). player는 누른 사람. */
public final class PlotLikedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlotInfo plot;
    private final int likes;

    public PlotLikedEvent(Player player, PlotInfo plot, int likes) {
        super(player);
        this.plot = plot;
        this.likes = likes;
    }

    public PlotInfo plot() {
        return plot;
    }

    public int likes() {
        return likes;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
