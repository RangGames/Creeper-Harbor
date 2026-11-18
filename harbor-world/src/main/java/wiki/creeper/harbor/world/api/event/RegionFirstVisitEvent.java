package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;

/** 이번 시즌 처음 들어간 지역 (region_visit 기록). Quest visit 목표, Economy 레시피 '방문' 해금, 비행선 노선 개방. */
public final class RegionFirstVisitEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;

    public RegionFirstVisitEvent(Player player, RegionInfo info) {
        super(player);
        this.info = info;
    }

    /** 지역 ID (Quest가 반사로 읽는 접근자). */
    public RegionId region() {
        return info.id();
    }

    /** 지역 요약. */
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
