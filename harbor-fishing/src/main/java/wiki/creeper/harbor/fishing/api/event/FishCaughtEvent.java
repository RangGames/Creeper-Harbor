package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * 포획 처리 후 (아이템 생성 뒤). Collection(도감·최대어), Quest(목표), Season(랭킹)이 듣는다. release_only 어종은 아이템 없이
 * released = true로 보낸다 (도감·EXP는 정상).
 */
public final class FishCaughtEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FishDef fish;
    private final HarborItem.Fish item;
    private final RegionId region;
    private final boolean released;
    private final boolean firstCatch;
    private final boolean capped;

    public FishCaughtEvent(
            Player player,
            FishDef fish,
            HarborItem.Fish item,
            RegionId region,
            boolean released,
            boolean firstCatch,
            boolean capped) {
        super(player);
        this.fish = fish;
        this.item = item;
        this.region = region;
        this.released = released;
        this.firstCatch = firstCatch;
        this.capped = capped;
    }

    public FishDef fish() {
        return fish;
    }

    public HarborItem.Fish item() {
        return item;
    }

    public RegionId region() {
        return region;
    }

    public boolean released() {
        return released;
    }

    public boolean firstCatch() {
        return firstCatch;
    }

    /** 직업 품질 상한에 걸렸는지 ('놓친 대어'). */
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
