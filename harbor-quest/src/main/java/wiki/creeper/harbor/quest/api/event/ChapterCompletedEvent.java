package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A main story chapter was cleared (final quest completed) or a chronicle recap. Announcements and
 * chronicle. Fired on the main thread.
 */
public final class ChapterCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int chapter;
    private final boolean recap;

    public ChapterCompletedEvent(UUID player, int chapter, boolean recap) {
        this.player = player;
        this.chapter = chapter;
        this.recap = recap;
    }

    public UUID player() {
        return player;
    }

    public int chapter() {
        return chapter;
    }

    public boolean recap() {
        return recap;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
