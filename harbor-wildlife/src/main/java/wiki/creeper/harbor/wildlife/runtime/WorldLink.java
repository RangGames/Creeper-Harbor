package wiki.creeper.harbor.wildlife.runtime;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.world.api.event.FishingSpotMovedEvent;
import wiki.creeper.harbor.world.api.event.SeaEventEndEvent;
import wiki.creeper.harbor.world.api.event.SeaEventStartEvent;

/**
 * World event link: fish school spot moved → school at that spot, great migration → target species
 * density boost ('17-3' 9.4).
 */
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
