package wiki.creeper.harbor.quest.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Summary of the rewards given on completion (QuestCompletedEvent). items is item ID × amount
 * notation.
 */
public record QuestRewardSummary(
        long gold,
        long mulbineul,
        long exp,
        int reputation,
        List<String> items,
        @Nullable String title,
        @Nullable String chronicle) {

    public QuestRewardSummary {
        items = List.copyOf(items);
    }
}
