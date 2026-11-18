package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;

/**
 * 미니게임까지 가지 못하고 끝난 캐스팅 (추가 제안, docs 20 3.2). 미리 감기·입질 놓침·미끼 교체로 끝나면 보낸다. 미니게임으로 끝난 세션은 {@link
 * MinigameEndEvent}를 본다. Wildlife는 이 이벤트로 접근 개체를 풀어도 된다 (ShadowDriver.release도 따로 불린다).
 */
public final class FishingSessionEndEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 끝난 이유. */
    public enum Reason {
        REELED_EARLY,
        MISSED_BITE,
        BAIT_CHANGED,
        INTERRUPTED
    }

    private final FishDef fish;
    private final Reason reason;

    public FishingSessionEndEvent(Player player, FishDef fish, Reason reason) {
        super(player);
        this.fish = fish;
        this.reason = reason;
    }

    public FishDef fish() {
        return fish;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
