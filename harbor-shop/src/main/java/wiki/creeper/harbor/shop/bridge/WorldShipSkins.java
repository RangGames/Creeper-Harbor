package wiki.creeper.harbor.shop.bridge;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.shop.api.CosmeticService;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.world.api.ShipSkinProvider;

/** World 연결 (선택). 선박 스킨 장착 여부를 World에 알린다. Harbor-World가 켜져 있을 때만 이 클래스를 불러온다. */
public final class WorldShipSkins {

    private WorldShipSkins() {}

    public static void register(Plugin plugin, CosmeticService cosmetics) {
        ShipSkinProvider provider =
                uuid -> cosmetics.equipped(uuid, CosmeticDef.Category.BOAT_SKIN).isPresent();
        plugin.getServer()
                .getServicesManager()
                .register(ShipSkinProvider.class, provider, plugin, ServicePriority.Normal);
    }
}
