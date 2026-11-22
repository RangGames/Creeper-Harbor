package wiki.creeper.harbor.economy.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;

/** After a craft is committed and the result is given (main thread). */
public final class ItemCraftedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final HarborId recipe;
    private final ItemId result;
    private final int amount;
    private final long gold;

    public ItemCraftedEvent(UUID player, HarborId recipe, ItemId result, int amount, long gold) {
        this.player = player;
        this.recipe = recipe;
        this.result = result;
        this.amount = amount;
        this.gold = gold;
    }

    public UUID player() {
        return player;
    }

    public HarborId recipe() {
        return recipe;
    }

    public ItemId result() {
        return result;
    }

    public int amount() {
        return amount;
    }

    public long gold() {
        return gold;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
