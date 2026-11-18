package wiki.creeper.harbor.shop.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;

/** 항구 축제 게이지가 바뀐 뒤 (harbor:festival 수신, 메인 스레드). reached는 이번에 목표에 닿았는지. */
public final class FestivalProgressEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HarborId festival;
    private final long totalKrw;
    private final long goalKrw;
    private final boolean reached;

    public FestivalProgressEvent(HarborId festival, long totalKrw, long goalKrw, boolean reached) {
        this.festival = festival;
        this.totalKrw = totalKrw;
        this.goalKrw = goalKrw;
        this.reached = reached;
    }

    public HarborId festival() {
        return festival;
    }

    public long totalKrw() {
        return totalKrw;
    }

    public long goalKrw() {
        return goalKrw;
    }

    public boolean reached() {
        return reached;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
