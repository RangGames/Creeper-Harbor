package wiki.creeper.harbor.season.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.SeasonId;

/** 주차가 바뀐 첫 틱에 메인 스레드에서 발생한다 (주차 잠금 퀘스트 개방 공지, 주간 의뢰 갱신). 서버마다 따로 발생한다. */
public final class SeasonWeekChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SeasonId season;
    private final int previousWeek;
    private final int week;

    public SeasonWeekChangedEvent(SeasonId season, int previousWeek, int week) {
        this.season = season;
        this.previousWeek = previousWeek;
        this.week = week;
    }

    public SeasonId season() {
        return season;
    }

    public int previousWeek() {
        return previousWeek;
    }

    /** 새 주차 (13 이상이면 연장 주). */
    public int week() {
        return week;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
