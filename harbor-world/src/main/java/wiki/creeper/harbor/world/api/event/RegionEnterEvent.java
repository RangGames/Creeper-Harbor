package wiki.creeper.harbor.world.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;

/** 지역에 들어왔을 때 (블록 이동·텔레포트·접속). from은 이전 지역 (없으면 null). */
public final class RegionEnterEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionInfo info;
    private final @Nullable RegionInfo from;

    public RegionEnterEvent(Player player, RegionInfo info, @Nullable RegionInfo from) {
        super(player);
        this.info = info;
        this.from = from;
    }

    /** 지역 ID (Quest가 반사로 읽는 접근자). */
    public RegionId region() {
        return info.id();
    }

    /** 지역 요약. */
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
