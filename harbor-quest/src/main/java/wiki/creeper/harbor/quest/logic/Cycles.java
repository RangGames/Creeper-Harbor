package wiki.creeper.harbor.quest.logic;

import java.time.LocalDate;
import wiki.creeper.harbor.quest.content.Repeat;

/**
 * 반복 주기 키 (progress JSON의 cycle, quest_board.cycle). 일일 = 게임 날짜(일일 초기화 기준), 주간 = 시즌·주차. 한 번만 하는
 * 퀘스트는 "once".
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
