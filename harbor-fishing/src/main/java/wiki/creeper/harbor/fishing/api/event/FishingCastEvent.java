package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.ShadowDriver;

/**
 * 캐스팅 판정이 끝나 찌가 대기에 들어갈 때 (docs 20 3.2). Wildlife가 같은 어종의 야생 개체를 찾아 {@link #claimShadow}로 그림자를 대행하면
 * Fishing은 대기 중 기본 그림자를 만들지 않는다. 핸들러가 끝난 뒤에 대행 여부를 본다.
 */
public final class FishingCastEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location hook;
    private final RegionId region;
    private final @Nullable ItemId bait;
    private final FishDef fish;
    private final int waitTicks;
    private final boolean school;
    private @Nullable ShadowDriver shadow;

    public FishingCastEvent(
            Player player,
            Location hook,
            RegionId region,
            @Nullable ItemId bait,
            FishDef fish,
            int waitTicks,
            boolean school) {
        super(player);
        this.hook = hook.clone();
        this.region = region;
        this.bait = bait;
        this.fish = fish;
        this.waitTicks = waitTicks;
        this.school = school;
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

    public Grade grade() {
        return fish.grade();
    }

    /** 입질까지 틱. */
    public int waitTicks() {
        return waitTicks;
    }

    /** 물고기 떼 위에서 던졌는지 (RegionKeyResolver가 알려 준 값). */
    public boolean school() {
        return school;
    }

    /**
     * 그림자를 대행한다 (Wildlife). 먼저 부른 드라이버만 받아들인다.
     *
     * @return 받아들였으면 true
     */
    public boolean claimShadow(ShadowDriver driver) {
        if (shadow != null) {
            return false;
        }
        shadow = driver;
        return true;
    }

    public @Nullable ShadowDriver shadow() {
        return shadow;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
