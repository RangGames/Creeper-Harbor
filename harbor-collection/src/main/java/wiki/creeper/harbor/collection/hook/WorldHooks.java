package wiki.creeper.harbor.collection.hook;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.collection.display.DisplayCaseManager;
import wiki.creeper.harbor.collection.display.ShopThemes;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.event.TreasureClaimedEvent;

/**
 * World integration (personal aquarium, treasure map hints; optional dependency). This class is
 * loaded only when Harbor-World is enabled.
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
        Bukkit.getPluginManager().registerEvents(display.clicks(), plugin);
        Bukkit.getPluginManager().registerEvents(display.chunks(), plugin);
        display.start();
        return display;
    }

    /**
     * Treasure map hints (docs 20 4.4 "grant paths"): a claimed map whose roll included a hint
     * (treasure.yml hint_chance) grants one random hint, preferring the map's region.
     */
    public static void installTreasureHints(Plugin plugin, HarborApi api, HintService hints) {
        Bukkit.getPluginManager().registerEvents(new TreasureHints(api, hints), plugin);
    }

    public record TreasureHints(HarborApi api, HintService hints) implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClaimed(TreasureClaimedEvent event) {
            if (!event.hint()) {
                return;
            }
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            Optional<FishId> fish = hints.grantRandom(uuid, event.region(), "treasure");
            if (fish.isEmpty()) {
                api.messages().send(player, "collection.hint.treasure_none");
                return;
            }
            int no =
                    api.content()
                            .snapshot()
                            .get(FishDef.TYPE, fish.get().id())
                            .map(FishDef::no)
                            .orElse(0);
            api.messages()
                    .send(
                            player,
                            "collection.hint.treasure",
                            Placeholder.unparsed("no", String.valueOf(no)),
                            Placeholder.unparsed(
                                    "level", String.valueOf(hints.level(uuid, fish.get()))));
        }
    }
}
