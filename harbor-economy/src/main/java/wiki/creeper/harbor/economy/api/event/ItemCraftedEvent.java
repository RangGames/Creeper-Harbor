package wiki.creeper.harbor.economy.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;

/** 제작이 커밋되고 결과를 지급한 뒤 (메인 스레드). */
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
