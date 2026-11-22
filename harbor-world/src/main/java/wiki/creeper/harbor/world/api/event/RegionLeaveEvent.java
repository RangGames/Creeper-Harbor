package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;

/** When leaving a region. to is the new region (null if none). */
public final class RegionLeaveEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;
    private final @Nullable RegionInfo to;

    public RegionLeaveEvent(Player player, RegionInfo info, @Nullable RegionInfo to) {
        super(player);
        this.info = info;
        this.to = to;
    }

    /** Region ID (accessor Quest reads via reflection). */
    public RegionId region() {
        return info.id();
    }

    /** Region summary. */
    public RegionInfo info() {
        return info;
    }

    public @Nullable RegionInfo to() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
