package wiki.creeper.harbor.fishing.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.fishing.api.CatchSource;

/**
 * After a catch is processed (after the item is created). Collection (collection, biggest fish),
 * Quest (objectives) and Season (rankings) listen. release_only species are sent without an item
 * with released = true (collection and EXP still apply).
 */
public final class FishCaughtEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FishDef fish;
    private final HarborItem.Fish item;
    private final RegionId region;
    private final boolean released;
    private final boolean firstCatch;
    private final boolean capped;
    private final CatchSource source;

    public FishCaughtEvent(
            Player player,
            FishDef fish,
            HarborItem.Fish item,
            RegionId region,
            boolean released,
            boolean firstCatch,
            boolean capped) {
        this(player, fish, item, region, released, firstCatch, capped, CatchSource.ROD);
    }

    public FishCaughtEvent(
            Player player,
            FishDef fish,
            HarborItem.Fish item,
            RegionId region,
            boolean released,
            boolean firstCatch,
            boolean capped,
            CatchSource source) {
        super(player);
        this.fish = fish;
        this.item = item;
        this.region = region;
        this.released = released;
        this.firstCatch = firstCatch;
        this.capped = capped;
        this.source = source;
    }

    /** Rod minigame (ROD) or guaranteed boss fight catch (BOSS). */
    public CatchSource source() {
        return source;
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

    /** Whether it hit the job quality cap ("the big one that got away"). */
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
