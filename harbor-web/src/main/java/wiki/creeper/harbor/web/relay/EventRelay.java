package wiki.creeper.harbor.web.relay;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Relays game events as external events (every server). Subscribes at MONITOR to the events in the
 * catalog ({@link EventCatalog}) only when their plugin is enabled, builds data with {@link
 * EventSerializer} and publishes to harbor:events via {@code api.events().emit}. Join and quit are
 * emitted directly.
 */
public final class EventRelay implements Listener {

    private final Plugin plugin;
    private final HarborApi api;
    private final EventSerializer serializer;
    private final List<String> exclude;
    private final Logger log;
    private int registered;

    public EventRelay(
            Plugin plugin,
            HarborApi api,
            EventSerializer serializer,
            List<String> exclude,
            Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.serializer = serializer;
        this.exclude = List.copyOf(exclude);
        this.log = log;
    }

    /**
     * @return number of subscribed events
     */
    public int register() {
        var pm = Bukkit.getPluginManager();
        for (EventCatalog.Entry entry : EventCatalog.ENTRIES) {
            if (Globs.any(exclude, entry.type())) {
                continue;
            }
            Plugin owner = pm.getPlugin(entry.plugin());
            if (owner == null || !owner.isEnabled()) {
                continue;
            }
            Class<? extends Event> type;
            try {
                type =
                        Class.forName(entry.className(), true, owner.getClass().getClassLoader())
                                .asSubclass(Event.class);
            } catch (ClassNotFoundException | ClassCastException | LinkageError e) {
                log.warn("외부 이벤트 {}의 클래스를 찾지 못했습니다: {}", entry.type(), entry.className());
                continue;
            }
            String externalType = entry.type();
            pm.registerEvent(
                    type,
                    this,
                    EventPriority.MONITOR,
                    (listener, event) -> {
                        if (type.isInstance(event)) {
                            relay(externalType, event);
                        }
                    },
                    plugin,
                    true);
            registered++;
        }
        pm.registerEvents(this, plugin);
        return registered;
    }

    private void relay(String type, Event event) {
        try {
            EventSerializer.Result r = serializer.serialize(event);
            api.events().emit(type, r.player(), r.playerName(), r.data());
        } catch (RuntimeException e) {
            log.warn("외부 이벤트 {} 변환 실패: {}", type, String.valueOf(e));
        }
    }

    /** server.* events (respecting the exclude list). */
    public void emitServer(String type, Map<String, ?> data) {
        if (!Globs.any(exclude, type)) {
            api.events().emit(type, null, null, data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!Globs.any(exclude, "server.player_join")) {
            api.events()
                    .emit(
                            "server.player_join",
                            event.getPlayer().getUniqueId(),
                            event.getPlayer().getName(),
                            Map.of("online", Bukkit.getOnlinePlayers().size()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!Globs.any(exclude, "server.player_quit")) {
            api.events()
                    .emit(
                            "server.player_quit",
                            event.getPlayer().getUniqueId(),
                            event.getPlayer().getName(),
                            Map.of("online", Math.max(0, Bukkit.getOnlinePlayers().size() - 1)));
        }
    }

    public int registered() {
        return registered;
    }

    /** Harbor ID → content name (type name = ID kind, if the entry has name()). */
    public static Optional<String> contentName(
            HarborApi api, wiki.creeper.harbor.common.id.HarborId id) {
        return api.content()
                .snapshot()
                .entry(id.category(), id)
                .flatMap(
                        entry -> {
                            try {
                                Object n = entry.getClass().getMethod("name").invoke(entry);
                                return n instanceof String s ? Optional.of(s) : Optional.empty();
                            } catch (ReflectiveOperationException | RuntimeException e) {
                                return Optional.empty();
                            }
                        });
    }
}
