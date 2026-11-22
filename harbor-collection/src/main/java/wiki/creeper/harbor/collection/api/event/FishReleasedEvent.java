package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;

/** One fish released (statistics, docs 20 4.2). Fired on the main thread after commit. */
public final class FishReleasedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final FishId fish;
    private final Grade grade;
    private final double size;

    public FishReleasedEvent(UUID player, FishId fish, Grade grade, double size) {
        this.player = player;
        this.fish = fish;
        this.grade = grade;
        this.size = size;
    }

    public UUID player() {
        return player;
    }

    public FishId fish() {
        return fish;
    }

    public Grade grade() {
        return grade;
    }

    public double size() {
        return size;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
