package wiki.creeper.harbor.season.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Fired on the main thread after a title is newly obtained (after DB commit) (proposed). Not fired
 * if the title was already owned. The player may be offline.
 */
public final class TitleGrantedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TitleId title;
    private final @Nullable String refId;

    public TitleGrantedEvent(UUID player, TitleId title, @Nullable String refId) {
        this.player = player;
        this.title = title;
        this.refId = refId;
    }

    public UUID player() {
        return player;
    }

    public TitleId title() {
        return title;
    }

    public @Nullable String refId() {
        return refId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
