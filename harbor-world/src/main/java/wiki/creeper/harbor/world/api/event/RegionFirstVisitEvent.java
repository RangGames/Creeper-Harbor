package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;

/**
 * First entry into a region this season (region_visit record). Quest visit objectives, Economy
 * recipe 'visit' unlocks, opening airship routes.
 */
public final class RegionFirstVisitEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;

    public RegionFirstVisitEvent(Player player, RegionInfo info) {
        super(player);
        this.info = info;
    }

    /** Region ID (accessor Quest reads via reflection). */
    public RegionId region() {
        return info.id();
    }

    /** Region summary. */
    public RegionInfo info() {
        return info;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
