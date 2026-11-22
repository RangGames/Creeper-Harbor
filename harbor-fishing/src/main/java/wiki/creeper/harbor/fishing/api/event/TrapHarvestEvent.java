package wiki.creeper.harbor.fishing.api.event;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * Trap harvest (docs 20 3.2). Fired after the harvest transaction commits and the items are given.
 * Quest (trap objectives) and Collection listen. Trap catches do not fire FishCaughtEvent, so
 * listen to this event if codex registration is needed.
 */
public final class TrapHarvestEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long trapId;
    private final RegionId region;
    private final List<HarborItem.Fish> fish;

    public TrapHarvestEvent(
            Player player, long trapId, RegionId region, List<HarborItem.Fish> fish) {
        super(player);
        this.trapId = trapId;
        this.region = region;
        this.fish = List.copyOf(fish);
    }

    public long trapId() {
        return trapId;
    }

    public RegionId region() {
        return region;
    }

    public List<HarborItem.Fish> fish() {
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
