package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;

/** 해도 완성도가 1%p 이상 바뀌었거나 보상 단계에 닿았을 때 (Quest chart_percent 목표). step은 이번에 받은 보상 단계 (없으면 0). */
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
