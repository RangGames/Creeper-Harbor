package wiki.creeper.harbor.quest.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;

/** 퀘스트 포기 (행 삭제 뒤). 메인 스레드에서 발생한다. */
public final class QuestAbandonedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final QuestId quest;

    public QuestAbandonedEvent(Player player, QuestId quest) {
        this.player = player;
        this.quest = quest;
    }

    public Player player() {
        return player;
    }

    public QuestId quest() {
        return quest;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
