package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.PlotInfo;

/** When a housing plot is unloaded (nobody there). */
public final class PlotUnloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlotInfo plot;

    public PlotUnloadedEvent(PlotInfo plot) {
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
