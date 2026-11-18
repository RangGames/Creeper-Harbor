package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;

/** 보물 지도를 수령했을 때 (커밋 후). hint면 Collection이 도감 힌트를 준다 (treasure.yml hint_chance). */
public final class TreasureClaimedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionId region;
    private final String siteId;
    private final String tier;
    private final long gold;
    private final boolean hint;

    public TreasureClaimedEvent(
            Player player, RegionId region, String siteId, String tier, long gold, boolean hint) {
        super(player);
        this.region = region;
        this.siteId = siteId;
        this.tier = tier;
        this.gold = gold;
        this.hint = hint;
    }

    public RegionId region() {
        return region;
    }

    public String siteId() {
        return siteId;
    }

    public String tier() {
        return tier;
    }

    public long gold() {
        return gold;
    }

    public boolean hint() {
        return hint;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
