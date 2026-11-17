package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;

/** 크기·품질 확정 직전. 보스전 품질 보너스·이벤트가 바꿀 수 있다. capped면 직업 상한에 걸린 것. */
public final class QualityRolledEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FishDef fish;
    private double sizeCm;
    private Quality quality;
    private final boolean capped;

    public QualityRolledEvent(
            Player player, FishDef fish, double sizeCm, Quality quality, boolean capped) {
        super(player);
        this.fish = fish;
        this.sizeCm = sizeCm;
        this.quality = quality;
        this.capped = capped;
    }

    public FishDef fish() {
        return fish;
    }

    public double sizeCm() {
        return sizeCm;
    }

    public void setSizeCm(double sizeCm) {
        this.sizeCm = sizeCm;
    }

    public Quality quality() {
        return quality;
    }

    public void setQuality(Quality quality) {
        this.quality = quality;
    }

    public boolean capped() {
        return capped;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
