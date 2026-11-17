package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/** 캐스팅 판정이 끝나 찌가 대기에 들어갈 때 (Wildlife가 주변 개체를 찌로 끌고 온다). */
public final class FishingCastEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location hook;
    private final RegionId region;
    private final @Nullable ItemId bait;
    private final FishDef fish;

    public FishingCastEvent(
            Player player, Location hook, RegionId region, @Nullable ItemId bait, FishDef fish) {
        super(player);
        this.hook = hook;
        this.region = region;
        this.bait = bait;
        this.fish = fish;
    }

    public Location hook() {
        return hook.clone();
    }

    public RegionId region() {
        return region;
    }

    public @Nullable ItemId bait() {
        return bait;
    }

    /** 판정된 어종 (그림자 등급·리그 선택용). */
    public FishDef fish() {
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
