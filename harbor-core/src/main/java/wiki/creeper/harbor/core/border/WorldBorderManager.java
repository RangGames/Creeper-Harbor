package wiki.creeper.harbor.core.border;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import wiki.creeper.harbor.core.api.WorldBorderService;

/**
 * Per-player world border arbitration (docs 20 0.2). Only the single highest-priority request is
 * sent to the player.
 */
public final class WorldBorderManager implements WorldBorderService, Listener {

    private final Map<UUID, EnumMap<Priority, Border>> requests = new ConcurrentHashMap<>();

    @Override
    public void request(Player player, Priority priority, Border border) {
        requests.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(Priority.class))
                .put(priority, border);
        apply(player);
    }

    @Override
    public void clear(Player player, Priority priority) {
        EnumMap<Priority, Border> map = requests.get(player.getUniqueId());
        if (map == null || map.remove(priority) == null) {
            return;
        }
        if (map.isEmpty()) {
            requests.remove(player.getUniqueId());
        }
        apply(player);
    }

    private void apply(Player player) {
        EnumMap<Priority, Border> map = requests.get(player.getUniqueId());
        if (map == null || map.isEmpty()) {
            player.setWorldBorder(null);
            return;
        }
        Border top = null;
        for (Border b : map.values()) {
            top = b; // EnumMap iterates in declaration order (low priority → high priority)
        }
        if (top == null) {
            player.setWorldBorder(null);
            return;
        }
        if (top.center().getWorld() != null && !top.center().getWorld().equals(player.getWorld())) {
            // Requests for another world are not shown (recomputed when the player changes worlds)
            player.setWorldBorder(null);
            return;
        }
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(top.center());
        border.setSize(top.size());
        border.setWarningDistance(top.warningDistance());
        border.setWarningTimeTicks(0);
        border.setDamageAmount(0);
        player.setWorldBorder(border);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requests.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (requests.containsKey(event.getPlayer().getUniqueId())) {
            apply(event.getPlayer());
        }
    }
}
