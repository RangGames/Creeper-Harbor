package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A piece of the truth was obtained. owned and total are the collected count after obtaining and
 * the total count. Fired on the main thread.
 */
public final class ChroniclePieceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String piece;
    private final int owned;
    private final int total;

    public ChroniclePieceEvent(UUID player, String piece, int owned, int total) {
        this.player = player;
        this.piece = piece;
        this.owned = owned;
        this.total = total;
    }

    public UUID player() {
        return player;
    }

    public String piece() {
        return piece;
    }

    public int owned() {
        return owned;
    }

    public int total() {
        return total;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
