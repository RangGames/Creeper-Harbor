package wiki.creeper.harbor.fishing.api.event;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * 통발 수확 (docs 20 3.2). 수확 트랜잭션이 커밋되고 아이템을 준 뒤에 보낸다. Quest(통발 목표)·Collection이 듣는다. 통발 수확물은
 * FishCaughtEvent를 보내지 않으므로 도감 등록이 필요하면 이 이벤트를 듣는다.
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
