package wiki.creeper.harbor.quest.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;

/** 목표 진행 (메모리 갱신 뒤). value는 갱신 후 값, count는 목표 수. 메인 스레드에서 발생한다. */
public final class QuestProgressEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final QuestId quest;
    private final String objective;
    private final int value;
    private final int count;

    public QuestProgressEvent(
            Player player, QuestId quest, String objective, int value, int count) {
        this.player = player;
        this.quest = quest;
        this.objective = objective;
        this.value = value;
        this.count = count;
    }

    public Player player() {
        return player;
    }

    public QuestId quest() {
        return quest;
    }

    public String objective() {
        return objective;
    }

    public int value() {
        return value;
    }

    public int count() {
        return count;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
