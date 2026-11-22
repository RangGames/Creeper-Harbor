package wiki.creeper.harbor.shop.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.content.CosmeticDef;

/**
 * After an equip slot changes (main thread). For rebuilding pet and boat appearance and refreshing
 * other modules' displays (title frame, aquarium theme). current null means unequipped.
 */
public final class CosmeticEquipEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CosmeticDef.Category slot;
    private final @Nullable HarborId previous;
    private final @Nullable HarborId current;

    public CosmeticEquipEvent(
            Player player,
            CosmeticDef.Category slot,
            @Nullable HarborId previous,
            @Nullable HarborId current) {
        super(player);
        this.slot = slot;
        this.previous = previous;
        this.current = current;
    }

    public CosmeticDef.Category slot() {
        return slot;
    }

    public @Nullable HarborId previous() {
        return previous;
    }

    public @Nullable HarborId current() {
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
