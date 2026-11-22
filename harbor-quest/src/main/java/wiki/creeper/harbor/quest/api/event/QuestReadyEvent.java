package wiki.creeper.harbor.quest.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * All tracked objectives are filled and the quest can be turned in (?). Fired on the main thread.
 */
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
