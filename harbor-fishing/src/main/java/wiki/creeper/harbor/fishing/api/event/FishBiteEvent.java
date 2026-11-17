package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;

/** 입질 시작. */
public final class FishBiteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FishDef fish;

    public FishBiteEvent(Player player, FishDef fish) {
        super(player);
        this.fish = fish;
    }

    public FishDef fish() {
        return fish;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
