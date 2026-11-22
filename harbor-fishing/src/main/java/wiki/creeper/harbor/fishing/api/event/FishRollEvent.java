package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;

/**
 * Right after the spawn roll. The result can be changed or cancelled (Boss: cancels it for a
 * summoning bait and starts the boss fight; Boss handles the bait).
 */
public final class FishRollEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private FishDef fish;
    private final @Nullable ItemId bait;
    private boolean cancelled;

    public FishRollEvent(Player player, FishDef fish, @Nullable ItemId bait) {
        super(player);
        this.fish = fish;
        this.bait = bait;
    }

    public FishDef fish() {
        return fish;
    }

    public void setFish(FishDef fish) {
        this.fish = fish;
    }

    public @Nullable ItemId bait() {
        return bait;
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
