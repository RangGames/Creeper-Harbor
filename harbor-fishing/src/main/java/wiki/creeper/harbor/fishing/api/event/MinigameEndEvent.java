package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.fishing.api.MinigameResult;

/** When the minigame ends (success or failure). */
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

    /** Time taken (ticks). */
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
