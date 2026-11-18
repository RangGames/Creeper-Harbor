package wiki.creeper.harbor.wildlife.api.event;

import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.wildlife.api.Medium;

/** 야생 개체(또는 무리)가 생기기 직전 (메인 스레드). 취소하면 만들지 않는다. */
public final class WildFishSpawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 생기는 이유. */
    public enum Reason {
        /** 플레이어 주변 자연 스폰. */
        NATURAL,
        /** 물고기 떼 포인트 (World FishingSpotMovedEvent). */
        SPOT,
        /** spawnScripted (이벤트·연출·관리 명령). */
        SCRIPTED,
        /** spawnSchool (경로 무리). */
        PATH
    }

    private final FishId fish;
    private final Location location;
    private final int count;
    private final Medium medium;
    private final Reason reason;
    private boolean cancelled;

    public WildFishSpawnEvent(
            FishId fish, Location location, int count, Medium medium, Reason reason) {
        this.fish = fish;
        this.location = location.clone();
        this.count = count;
        this.medium = medium;
        this.reason = reason;
    }

    public FishId fish() {
        return fish;
    }

    public Location location() {
        return location.clone();
    }

    /** 무리 마릿수 (1이면 혼자). */
    public int count() {
        return count;
    }

    public Medium medium() {
        return medium;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
