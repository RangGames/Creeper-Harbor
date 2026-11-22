package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.collection.api.CollectionScope;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * First registration in the season or cumulative collection (Quest condition re-evaluation, Shop
 * pass XP).
 */
public final class CollectionRegisteredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final FishId fish;
    private final CollectionScope scope;
    private final Grade grade;

    public CollectionRegisteredEvent(UUID player, FishId fish, CollectionScope scope, Grade grade) {
        this.player = player;
        this.fish = fish;
        this.scope = scope;
        this.grade = grade;
    }

    public UUID player() {
        return player;
    }

    public FishId fish() {
        return fish;
    }

    public CollectionScope scope() {
        return scope;
    }

    public Grade grade() {
        return grade;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
