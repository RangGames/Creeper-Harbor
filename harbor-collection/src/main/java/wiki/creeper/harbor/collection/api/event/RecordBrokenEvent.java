package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;

/** 개인·서버 신기록 (Season 명예의 전당 잠정 기록). previous는 이전 기록 크기 (없으면 0). */
public final class RecordBrokenEvent extends Event {

    /** 기록 범위. */
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
