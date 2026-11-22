package wiki.creeper.harbor.season.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Fired on the main thread after the equipped title changes (proposed; name tag and chat prefix
 * refresh). title null means unequipped.
 */
public final class TitleEquippedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final @Nullable TitleId title;

    public TitleEquippedEvent(UUID player, @Nullable TitleId title) {
        this.player = player;
        this.title = title;
    }

    public UUID player() {
        return player;
    }

    public @Nullable TitleId title() {
        return title;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
