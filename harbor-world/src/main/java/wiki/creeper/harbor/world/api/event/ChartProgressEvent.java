package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * When sea chart completion changed by at least 1 percentage point or a reward step was reached
 * (Quest chart_percent objective). step is the reward step just received (0 if none).
 */
public final class ChartProgressEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionId region;
    private final double percent;
    private final int step;

    public ChartProgressEvent(Player player, RegionId region, double percent, int step) {
        super(player);
        this.region = region;
        this.percent = percent;
        this.step = step;
    }

    public RegionId region() {
        return region;
    }

    public double percent() {
        return percent;
    }

    public int step() {
        return step;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
