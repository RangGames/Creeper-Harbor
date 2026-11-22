package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;

/**
 * When entering a region (block move, teleport, join). from is the previous region (null if none).
 */
public final class RegionEnterEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;
    private final @Nullable RegionInfo from;

    public RegionEnterEvent(Player player, RegionInfo info, @Nullable RegionInfo from) {
        super(player);
        this.info = info;
        this.from = from;
    }

    /** Region ID (accessor Quest reads via reflection). */
    public RegionId region() {
        return info.id();
    }

    /** Region summary. */
    public RegionInfo info() {
        return info;
    }

    public @Nullable RegionInfo from() {
        return from;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
