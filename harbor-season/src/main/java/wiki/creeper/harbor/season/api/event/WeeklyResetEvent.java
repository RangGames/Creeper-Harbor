package wiki.creeper.harbor.season.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.SeasonId;

/** 주간 초기화 시각(기본 월요일 06:00)에 메인 스레드에서 발생한다. 서버마다 따로 발생한다. */
public final class WeeklyResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SeasonId season;
    private final int week;

    public WeeklyResetEvent(SeasonId season, int week) {
        this.season = season;
        this.week = week;
    }

    public SeasonId season() {
        return season;
    }

    /** 초기화 직후의 시즌 주차. */
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
