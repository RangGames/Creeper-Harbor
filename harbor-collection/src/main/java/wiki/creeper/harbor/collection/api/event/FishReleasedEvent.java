package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;

/** 방류 한 마리 (통계, docs 20 4.2). 커밋 후 메인 스레드에서 발생한다. */
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
