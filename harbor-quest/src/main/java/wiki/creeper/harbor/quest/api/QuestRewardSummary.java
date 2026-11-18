package wiki.creeper.harbor.quest.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** 완료 때 준 보상 요약 (QuestCompletedEvent). items는 아이템 ID × 수량 표기. */
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
