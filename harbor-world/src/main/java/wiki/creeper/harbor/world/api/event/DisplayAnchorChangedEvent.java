package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.PlotInfo;

/** When a personal aquarium tank is placed or removed (Collection redraws the display). */
public final class DisplayAnchorChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlotInfo plot;
    private final DisplayAnchor anchor;
    private final boolean added;

    public DisplayAnchorChangedEvent(PlotInfo plot, DisplayAnchor anchor, boolean added) {
        this.plot = plot;
        this.anchor = anchor;
        this.added = added;
    }

    public PlotInfo plot() {
        return plot;
    }

    public DisplayAnchor anchor() {
        return anchor;
    }

    public boolean added() {
        return added;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
