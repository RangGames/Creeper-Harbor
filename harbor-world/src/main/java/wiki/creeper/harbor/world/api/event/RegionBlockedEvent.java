package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;
import wiki.creeper.harbor.world.api.UnlockStatus;

/** When pushed back for not meeting entry conditions (docs 20 8.4 'push-back'). */
public final class RegionBlockedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;
    private final UnlockStatus status;

    public RegionBlockedEvent(Player player, RegionInfo info, UnlockStatus status) {
        super(player);
        this.info = info;
        this.status = status;
    }

    /** Region ID (accessor Quest reads via reflection). */
    public RegionId region() {
        return info.id();
    }

    /** Region summary. */
    public RegionInfo info() {
        return info;
    }

    public UnlockStatus status() {
        return status;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
