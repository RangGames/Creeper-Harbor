package wiki.creeper.harbor.wildlife.runtime;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.world.api.event.FishingSpotMovedEvent;
import wiki.creeper.harbor.world.api.event.SeaEventEndEvent;
import wiki.creeper.harbor.world.api.event.SeaEventStartEvent;

/** World 이벤트 연결: 물고기 떼 포인트 이동 → 그 자리 무리, 물고기 대이동 → 대상 어종 밀도 boost ('17-3' 9.4). */
final class WorldLink implements Listener {

    private final WildlifeManager manager;

    WorldLink(WildlifeManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onSpotMoved(FishingSpotMovedEvent event) {
        manager.spotMoved(event.spot(), event.previous());
    }

    @EventHandler
    public void onSeaEventStart(SeaEventStartEvent event) {
        manager.seaEventStarted(event.seaEvent());
    }

    @EventHandler
    public void onSeaEventEnd(SeaEventEndEvent event) {
        manager.seaEventEnded(event.seaEvent());
    }
}
