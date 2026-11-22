package wiki.creeper.harbor.fishing.food;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.fishing.content.FoodBuffs;

/**
 * Food buff replacement rule (docs 20 0.1, "7. Balance design" 4.2 "only one food buff"). Eating a
 * new food removes the previous buff source and replaces it with the new buff. Eating the same food
 * again restarts the timer (it does not add up). Duration = base × (1 + food_buff_duration). No
 * server API (unit tests).
 */
public final class FoodRule {

    /**
     * Replacement result.
     *
     * @param removeSource source of the previous buff to remove (null if none)
     * @param next the new buff to apply
     */
    public record Replacement(@Nullable String removeSource, ActiveFood next) {}

    private FoodRule() {}

    public static String source(ItemId item) {
        return "food:" + item.id().name();
    }

    public static Duration duration(FoodBuffs.Buff buff, double durationBonus) {
        double mult = Math.max(0, 1 + durationBonus);
        return Duration.ofMillis(Math.round(buff.duration().toMillis() * mult));
    }

    public static Replacement replace(
            @Nullable ActiveFood current, FoodBuffs.Buff buff, Instant now, double durationBonus) {
        // Remove the previous buff's source whether or not it expired (cleans up leftover
        // modifiers)
        String remove = current == null ? null : current.source();
        return new Replacement(
                remove, new ActiveFood(buff.item(), now.plus(duration(buff, durationBonus))));
    }
}
