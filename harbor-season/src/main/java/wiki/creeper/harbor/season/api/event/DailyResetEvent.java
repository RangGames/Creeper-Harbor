package wiki.creeper.harbor.season.api.event;

import java.time.LocalDate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 일일 초기화 시각(기본 06:00)에 메인 스레드에서 발생한다. 서버마다 따로 발생한다. */
public final class DailyResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LocalDate gameDay;

    public DailyResetEvent(LocalDate gameDay) {
        this.gameDay = gameDay;
    }

    /** 새 게임 날짜 (Core HarborTime.gameDay). */
    public LocalDate gameDay() {
        return gameDay;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
