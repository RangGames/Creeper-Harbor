package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.api.QuestRewardSummary;

/**
 * A quest was completed (after the completion row is written and rewards are given). Shop pass
 * experience, statistics. cycle is the repeat cycle key (once · d2027-05-03 · w1-3). Fired on the
 * main thread.
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
