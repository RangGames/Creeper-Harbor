package wiki.creeper.harbor.shop.bridge;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.shop.api.CosmeticService;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.world.api.ShipSkinProvider;

/**
 * World bridge (optional). Tells World whether a ship skin is equipped. This class is loaded only
 * when Harbor-World is enabled.
 */
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
