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
 * When the cast roll is done and the bobber starts waiting (docs 20 3.2). If Wildlife finds a wild
 * individual of the same species and takes over the shadow via {@link #claimShadow}, Fishing does
 * not create the default shadow while waiting. The takeover is checked after the handlers finish.
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

    /** The rolled species (for shadow grade and rig selection). */
    public FishDef fish() {
        return fish;
    }

    public Grade grade() {
        return fish.grade();
    }

    /** Ticks until the bite. */
    public int waitTicks() {
        return waitTicks;
    }

    /** Whether the cast was over a fish school (value reported by RegionKeyResolver). */
    public boolean school() {
        return school;
    }

    /**
     * Takes over the shadow (Wildlife). Only the first driver to call is accepted.
     *
     * @return true if accepted
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
