package wiki.creeper.harbor.quest.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;

/** 퀘스트 수락 (수락 행을 쓴 뒤 메인 스레드). NPC 표시 갱신·통계. 메인 스레드에서 발생한다. */
public final class QuestAcceptedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final QuestId quest;
    private final String type;

    public QuestAcceptedEvent(Player player, QuestId quest, String type) {
        this.player = player;
        this.quest = quest;
        this.type = type;
    }

    public Player player() {
        return player;
    }

    public QuestId quest() {
        return quest;
    }

    public String type() {
        return type;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
