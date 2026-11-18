package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.PlotInfo;

/** 하우징 플롯을 메모리에 올렸을 때 (주인 접속·방문). */
public final class PlotLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlotInfo plot;

    public PlotLoadedEvent(PlotInfo plot) {
        this.plot = plot;
    }

    public PlotInfo plot() {
        return plot;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
