package wiki.creeper.harbor.core.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Harbor GUI가 열려 있으면 아이템 이동을 모두 막고 버튼 클릭만 처리한다. */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu menu) {
            event.setCancelled(true);
            if (event.getRawSlot() >= 0
                    && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                menu.handleClick(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu menu
                && event.getPlayer() instanceof Player player) {
            menu.handleClose(player);
        }
    }
}
