package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Personal or server new record (Season hall of fame provisional record). previous is the previous
 * record size (0 if none).
 */
public final class RecordBrokenEvent extends Event {

    /** Record scope. */
    public enum Scope {
        PERSONAL,
        SERVER
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final FishId fish;
    private final String league;
    private final Scope scope;
    private final double size;
    private final double previous;

    public RecordBrokenEvent(
            UUID player, FishId fish, String league, Scope scope, double size, double previous) {
        this.player = player;
        this.fish = fish;
        this.league = league;
        this.scope = scope;
        this.size = size;
        this.previous = previous;
    }

    public UUID player() {
        return player;
    }

    public FishId fish() {
        return fish;
    }

    public String league() {
        return league;
    }

    public Scope scope() {
        return scope;
    }

    public double size() {
        return size;
    }

    public double previous() {
        return previous;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
