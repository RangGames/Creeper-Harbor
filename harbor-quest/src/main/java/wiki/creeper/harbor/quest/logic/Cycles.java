package wiki.creeper.harbor.quest.logic;

import java.time.LocalDate;
import wiki.creeper.harbor.quest.content.Repeat;

/**
 * Repeat cycle key (cycle in the progress JSON, quest_board.cycle). Daily = game date (daily reset
 * boundary), weekly = season and week. One-time quests use "once".
 */
public final class Cycles {

    public static final String ONCE = "once";

    private Cycles() {}

    public static String daily(LocalDate gameDay) {
        return "d" + gameDay;
    }

    public static String weekly(int season, int week) {
        return "w" + season + "-" + week;
    }

    public static String of(Repeat repeat, LocalDate gameDay, int season, int week) {
        return switch (repeat) {
            case NONE -> ONCE;
            case DAILY -> daily(gameDay);
            case WEEKLY -> weekly(season, week);
        };
    }
}
