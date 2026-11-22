package wiki.creeper.harbor.core.api.event;

import java.util.Set;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.content.ContentSnapshot;

/**
 * Fired on the main thread after a new content snapshot is applied. Modules with caches rebuild
 * them here.
 */
public final class ContentReloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentSnapshot snapshot;
    private final Set<String> reloadedTypes;

    public ContentReloadedEvent(ContentSnapshot snapshot, Set<String> reloadedTypes) {
        this.snapshot = snapshot;
        this.reloadedTypes = Set.copyOf(reloadedTypes);
    }

    public ContentSnapshot snapshot() {
        return snapshot;
    }

    /** Names of the content types reloaded this time. */
    public Set<String> reloadedTypes() {
        return reloadedTypes;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
