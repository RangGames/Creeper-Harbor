package wiki.creeper.harbor.core.item;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * On login, aligns Harbor item models in the inventory and ender chest with the items.custom_models
 * setting (switching before/after the resource pack rollout).
 */
public final class ItemModelSync implements Listener {

    private final ItemManager items;

    public ItemModelSync(ItemManager items) {
        this.items = items;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        items.syncModels(player.getInventory());
        items.syncModels(player.getEnderChest());
    }
}
