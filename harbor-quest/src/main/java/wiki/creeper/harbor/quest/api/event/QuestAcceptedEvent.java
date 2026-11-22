package wiki.creeper.harbor.quest.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * A quest was accepted (on the main thread after the accept row is written). NPC marker refresh,
 * statistics. Fired on the main thread.
 */
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
