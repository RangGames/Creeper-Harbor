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

/** 개인 세계 경계 중재 (docs 20 0.2). 가장 높은 우선순위 요청 하나만 플레이어에게 보낸다. */
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
            top = b; // EnumMap은 선언 순서(낮은 순위 → 높은 순위)로 돈다
        }
        if (top == null) {
            player.setWorldBorder(null);
            return;
        }
        if (top.center().getWorld() != null && !top.center().getWorld().equals(player.getWorld())) {
            // 다른 월드의 요청은 보여 주지 않는다 (월드를 옮기면 다시 계산)
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
