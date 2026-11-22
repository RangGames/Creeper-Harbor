package wiki.creeper.harbor.collection.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.display.DisplayCaseManager;
import wiki.creeper.harbor.collection.display.ShopThemes;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.world.api.HousingService;

/**
 * World integration (personal aquarium, optional dependency). This class is loaded only when
 * Harbor-World is enabled.
 */
public final class WorldHooks {

    private WorldHooks() {}

    /** Enables personal aquarium displays. null if World has not registered HousingService. */
    public static @Nullable DisplayCaseManager install(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            Logger log,
            NpcBridge npc,
            ShopThemes themes) {
        HousingService housing = Bukkit.getServicesManager().load(HousingService.class);
        if (housing == null) {
            log.warn("Harbor-World가 HousingService를 등록하지 않아 개인 어항 전시를 끕니다");
            return null;
        }
        DisplayCaseManager display =
                new DisplayCaseManager(plugin, api, tasks, log, housing, npc, themes);
        Bukkit.getPluginManager().registerEvents(display, plugin);
        display.start();
        return display;
    }
}
