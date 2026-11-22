package wiki.creeper.harbor.core.api.event;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * A player is placing a custom block (main thread, proposed). Fired from the vanilla
 * BlockPlaceEvent at HIGHEST priority (after protection plugins), before the note block state is
 * set. Cancelling cancels the placement.
 */
public final class CustomBlockPlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Block block;
    private final HarborId customBlock;
    private boolean cancelled;

    public CustomBlockPlaceEvent(Player player, Block block, HarborId customBlock) {
        this.player = player;
        this.block = block;
        this.customBlock = customBlock;
    }

    public Player player() {
        return player;
    }

    public Block block() {
        return block;
    }

    /** Custom block ID (harbor:block/…). */
    public HarborId customBlock() {
        return customBlock;
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
