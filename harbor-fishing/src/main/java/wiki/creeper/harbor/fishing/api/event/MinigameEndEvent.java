package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.fishing.api.MinigameResult;

/** 미니게임이 끝났을 때 (성공·실패 모두). */
public final class MinigameEndEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FishDef fish;
    private final MinigameResult result;
    private final int ticks;

    public MinigameEndEvent(Player player, FishDef fish, MinigameResult result, int ticks) {
        super(player);
        this.fish = fish;
        this.result = result;
        this.ticks = ticks;
    }

    public FishDef fish() {
        return fish;
    }

    public MinigameResult result() {
        return result;
    }

    /** 걸린 시간 (틱). */
    public int ticks() {
        return ticks;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
