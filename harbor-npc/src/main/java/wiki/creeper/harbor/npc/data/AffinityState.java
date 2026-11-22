package wiki.creeper.harbor.npc.data;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Affinity state toward one NPC (one npc_affinity row).
 *
 * @param giftDay game date of the last gift
 * @param giftsToday gifts given on giftDay
 * @param greetDay date of the last first-talk reward
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
