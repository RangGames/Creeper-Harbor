package wiki.creeper.harbor.core.item;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** 접속할 때 인벤토리·엔더 상자의 Harbor 아이템 모델을 items.custom_models 설정에 맞춘다 (리소스팩 도입 전후 전환). */
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
