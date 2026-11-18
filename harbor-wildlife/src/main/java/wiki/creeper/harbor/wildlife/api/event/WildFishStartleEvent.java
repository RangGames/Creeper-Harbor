package wiki.creeper.harbor.wildlife.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.wildlife.api.WildFish;

/** 플레이어 때문에 개체가 놀라 도망쳤을 때 (계산 스레드가 정한 뒤 메인 스레드에서 알림). 무리로 전파된 개체마다 따로 보내지 않고 처음 놀란 개체만 보낸다. */
public final class WildFishStartleEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WildFish fish;

    public WildFishStartleEvent(WildFish fish, Player player) {
        super(player);
        this.fish = fish;
    }

    public WildFish fish() {
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
