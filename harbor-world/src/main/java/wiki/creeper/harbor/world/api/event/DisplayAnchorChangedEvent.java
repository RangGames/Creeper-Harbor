package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.PlotInfo;

/** 개인 어항 수조가 놓이거나 치워졌을 때 (Collection이 전시를 다시 그린다). */
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
