package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.api.QuestRewardSummary;

/**
 * 퀘스트 완료 (완료 행을 쓰고 보상을 준 뒤). Shop 패스 경험치, 통계. cycle은 반복 주기 키 (once · d2027-05-03 · w1-3). 메인 스레드에서
 * 발생한다.
 */
public final class QuestCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final QuestId quest;
    private final String type;
    private final String cycle;
    private final QuestRewardSummary rewards;

    public QuestCompletedEvent(
            UUID player, QuestId quest, String type, String cycle, QuestRewardSummary rewards) {
        this.player = player;
        this.quest = quest;
        this.type = type;
        this.cycle = cycle;
        this.rewards = rewards;
    }

    public UUID player() {
        return player;
    }

    public QuestId quest() {
        return quest;
    }

    public String type() {
        return type;
    }

    public String cycle() {
        return cycle;
    }

    public QuestRewardSummary rewards() {
        return rewards;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
