package wiki.creeper.harbor.npc.data;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * NPC 한 명에 대한 호감도 상태 (npc_affinity 한 행).
 *
 * @param giftDay 마지막으로 선물한 게임 날짜
 * @param giftsToday giftDay에 준 선물 수
 * @param greetDay 마지막 첫 대화 보상 날짜
 */
public record AffinityState(
        int points, @Nullable LocalDate giftDay, int giftsToday, @Nullable LocalDate greetDay) {

    public static final AffinityState EMPTY = new AffinityState(0, null, 0, null);

    public AffinityState withPoints(int newPoints) {
        return new AffinityState(newPoints, giftDay, giftsToday, greetDay);
    }

    public int giftsOn(LocalDate day) {
        return day.equals(giftDay) ? giftsToday : 0;
    }

    public AffinityState gifted(LocalDate day) {
        return new AffinityState(points, day, giftsOn(day) + 1, greetDay);
    }

    public boolean greetedOn(LocalDate day) {
        return day.equals(greetDay);
    }

    public AffinityState greeted(LocalDate day) {
        return new AffinityState(points, giftDay, giftsToday, day);
    }
}
