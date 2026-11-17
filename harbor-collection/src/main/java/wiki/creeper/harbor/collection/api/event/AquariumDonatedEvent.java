package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;

/** 수족관 기부 1마리마다 (Quest donate 목표). */
public final class AquariumDonatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final FishId fish;
    private final Grade grade;
    private final Quality quality;
    private final int points;

    public AquariumDonatedEvent(
            UUID player, FishId fish, Grade grade, Quality quality, int points) {
        this.player = player;
        this.fish = fish;
        this.grade = grade;
        this.quality = quality;
        this.points = points;
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

    public Quality quality() {
        return quality;
    }

    public int points() {
        return points;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
