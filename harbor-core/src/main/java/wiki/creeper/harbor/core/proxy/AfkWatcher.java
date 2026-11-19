package wiki.creeper.harbor.core.proxy;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.ProxyBridge;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 잠수 시 로비로 (본 서버만, 추가 제안). 움직임·시점·키 입력·채팅·명령·클릭이 balance.yml afk.lobby_minutes 동안 없으면 로비로 보낸다. 프록시가
 * 자리 유예를 주고(그동안 돌아오면 대기열 앞줄) 로비에서 자동 출항하지 않게 한다. afk.warn_seconds 전에 한 번 알린다. harbor.afk.exempt 권한이
 * 있으면 보내지 않는다. afk.lobby_minutes가 0이면 끈다.
 */
public final class AfkWatcher implements Listener {

    private static final String EXEMPT = "harbor.afk.exempt";

    private static final long CHECK_TICKS = 20L * 10;

    private final Supplier<ContentSnapshot> content;
    private final ProxyBridge proxy;
    private final MessageService messages;
    private final Tasks tasks;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private final Set<UUID> sending = new HashSet<>();

    public AfkWatcher(
            Supplier<ContentSnapshot> content,
            ProxyBridge proxy,
            MessageService messages,
            Tasks tasks) {
        this.content = content;
        this.proxy = proxy;
        this.messages = messages;
        this.tasks = tasks;
    }

    public void start() {
        long now = System.currentTimeMillis();
        Bukkit.getOnlinePlayers().forEach(p -> lastActive.put(p.getUniqueId(), now));
        var _ = tasks.timer(CHECK_TICKS, CHECK_TICKS, this::check);
    }

    private void active(Player player) {
        UUID uuid = player.getUniqueId();
        lastActive.put(uuid, System.currentTimeMillis());
        warned.remove(uuid);
    }

    private void check() {
        Balance balance = content.get().single(Balance.TYPE);
        double minutes = balance.number("afk.lobby_minutes", 15);
        if (minutes <= 0) {
            return;
        }
        long limit = Math.round(minutes * 60_000);
        long warnAt = limit - Math.round(balance.number("afk.warn_seconds", 60) * 1000);
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (player.hasPermission(EXEMPT) || sending.contains(uuid)) {
                continue;
            }
            long idle = now - lastActive.computeIfAbsent(uuid, k -> now);
            if (idle >= limit) {
                sending.add(uuid);
                warned.remove(uuid);
                messages.send(player, "afk.moved");
                var _ =
                        proxy.requestLobby(uuid, "afk")
                                .whenComplete(
                                        (result, error) -> {
                                            sending.remove(uuid);
                                            lastActive.put(uuid, System.currentTimeMillis());
                                        });
            } else if (idle >= warnAt && warned.add(uuid)) {
                long left = Math.max(1, (limit - idle) / 1000);
                messages.send(
                        player, "afk.warn", Placeholder.unparsed("seconds", String.valueOf(left)));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        active(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastActive.remove(uuid);
        warned.remove(uuid);
        sending.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() || event.hasChangedOrientation()) {
            active(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            active(player);
        }
    }
}
