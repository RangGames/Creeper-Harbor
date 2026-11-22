package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.world.api.PlotInfo;

/** When a visitor liked a plot (after commit). player is the one who liked it. */
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
