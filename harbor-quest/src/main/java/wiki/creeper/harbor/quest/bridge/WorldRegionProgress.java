package wiki.creeper.harbor.quest.bridge;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.world.api.RegionService;

/**
 * World link (optional). Registers World's "highest unlocked region" (full entry requirements) with
 * a higher priority than Quest's default implementation (level + unlock quest). This class is
 * loaded only when Harbor-World is enabled.
 */
public final class WorldRegionProgress {

    private WorldRegionProgress() {}

    public static boolean register(Plugin plugin) {
        RegionService regions = Bukkit.getServicesManager().load(RegionService.class);
        if (regions == null) {
            return false;
        }
        RegionProgressProvider provider =
                uuid -> {
                    Player player = Bukkit.getPlayer(uuid);
                    return player == null
                            ? Optional.empty()
                            : Optional.of(regions.highestUnlocked(player).id());
                };
        Bukkit.getServicesManager()
                .register(RegionProgressProvider.class, provider, plugin, ServicePriority.High);
        return true;
    }
}
