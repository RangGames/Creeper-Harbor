package wiki.creeper.harbor.collection.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 수족관 단계 상승 (Quest 클라이맥스 판정). 단계를 올린 서버에서만 발생한다. */
public final class AquariumStageChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int season;
    private final int stage;

    public AquariumStageChangedEvent(int season, int stage) {
        this.season = season;
        this.stage = stage;
    }

    public int season() {
        return season;
    }

    public int stage() {
        return stage;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
