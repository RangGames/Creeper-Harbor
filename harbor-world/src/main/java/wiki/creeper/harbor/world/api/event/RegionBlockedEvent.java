package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;
import wiki.creeper.harbor.world.api.UnlockStatus;

/** 진입 조건을 못 채워 밀려났을 때 (docs 20 8.4 '밀어내기'). */
public final class RegionBlockedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;
    private final UnlockStatus status;

    public RegionBlockedEvent(Player player, RegionInfo info, UnlockStatus status) {
        super(player);
        this.info = info;
        this.status = status;
    }

    /** 지역 ID (Quest가 반사로 읽는 접근자). */
    public RegionId region() {
        return info.id();
    }

    /** 지역 요약. */
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
