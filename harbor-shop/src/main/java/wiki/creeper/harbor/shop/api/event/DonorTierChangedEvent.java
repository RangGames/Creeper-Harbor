package wiki.creeper.harbor.shop.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.donor.DonorTier;

/** 접속 중인 플레이어의 후원 등급이 바뀐 뒤 (메인 스레드). 이름 색·채팅 아이콘·어항 테마 칸 갱신용. */
public final class DonorTierChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final DonorTier previous;
    private final DonorTier current;

    public DonorTierChangedEvent(UUID player, DonorTier previous, DonorTier current) {
        this.player = player;
        this.previous = previous;
        this.current = current;
    }

    public UUID player() {
        return player;
    }

    public DonorTier previous() {
        return previous;
    }

    public DonorTier current() {
        return current;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
