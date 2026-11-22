package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.api.QuestState;

/** State change (NPC marker refresh). from and to are QuestState. Fired on the main thread. */
public final class QuestStateChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final QuestId quest;
    private final QuestState from;
    private final QuestState to;

    public QuestStateChangedEvent(UUID player, QuestId quest, QuestState from, QuestState to) {
        this.player = player;
        this.quest = quest;
        this.from = from;
        this.to = to;
    }

    public UUID player() {
        return player;
    }

    public QuestId quest() {
        return quest;
    }

    public QuestState from() {
        return from;
    }

    public QuestState to() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
