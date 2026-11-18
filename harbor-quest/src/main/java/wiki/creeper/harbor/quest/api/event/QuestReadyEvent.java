package wiki.creeper.harbor.quest.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;

/** 추적 목표를 모두 채워 보고할 수 있게 됨 (?). 메인 스레드에서 발생한다. */
public final class QuestReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final QuestId quest;

    public QuestReadyEvent(Player player, QuestId quest) {
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
