package wiki.creeper.harbor.web.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Relays game events as external events (every server). Subscribes at MONITOR to the events in the
 * catalog ({@link EventCatalog}) only when their plugin is enabled, builds data with {@link
 * EventSerializer} and publishes to harbor:events via {@code api.events().emit}. Join and quit are
 * emitted directly.
 *
 * <p>When the event names a player by UUID only, the name is filled in (online player on this
 * server, recent-name cache, else the players table on a virtual thread; the event is then
 * published once the lookup finishes). Without a players row it goes out without a name.
 */
public final class EventRelay implements Listener {

    private final Plugin plugin;
    private final HarborApi api;
    private final EventSerializer serializer;
    private final List<String> exclude;
    private final Logger log;
    private int registered;

    private static final int NAME_CACHE = 4096;

    /** UUID → last known name (filled from joins, online lookups and DB lookups). */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

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
        EventSerializer.Result r;
        try {
            r = serializer.serialize(event);
        } catch (RuntimeException e) {
            log.warn("외부 이벤트 {} 변환 실패: {}", type, String.valueOf(e));
            return;
        }
        UUID player = r.player();
        String name = r.playerName();
        if (player == null || name != null) {
            api.events().emit(type, player, name, r.data());
            return;
        }
        // Module events mostly carry a UUID only: fill player_name from this server's online
        // players, then a small cache, then the players table (off the main thread)
        String known = knownName(player);
        if (known != null) {
            api.events().emit(type, player, known, r.data());
            return;
        }
        var _ =
                api.database()
                        .async(c -> lookupName(c, player))
                        .handle(
                                (found, error) -> {
                                    String resolved = null;
                                    if (found != null && found.isPresent()) {
                                        resolved = found.get();
                                        remember(player, resolved);
                                    }
                                    api.events().emit(type, player, resolved, r.data());
                                    return null;
                                });
    }

    /** Online player on this server or a recently seen name (any thread). */
    private @Nullable String knownName(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            String n = online.getName();
            remember(player, n);
            return n;
        }
        return names.get(player);
    }

    private void remember(UUID player, String name) {
        if (names.size() >= NAME_CACHE) {
            names.clear(); // crude bound; names are cheap to look up again
        }
        names.put(player, name);
    }

    private static Optional<String> lookupName(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
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
        remember(event.getPlayer().getUniqueId(), event.getPlayer().getName());
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
