package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;

/** 지역을 나갔을 때. to는 새 지역 (없으면 null). */
public final class RegionLeaveEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;
    private final @Nullable RegionInfo to;

    public RegionLeaveEvent(Player player, RegionInfo info, @Nullable RegionInfo to) {
        super(player);
        this.info = info;
        this.to = to;
    }

    /** 지역 ID (Quest가 반사로 읽는 접근자). */
    public RegionId region() {
        return info.id();
    }

    /** 지역 요약. */
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
