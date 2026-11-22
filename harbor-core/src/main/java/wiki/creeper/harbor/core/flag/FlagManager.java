package wiki.creeper.harbor.core.flag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.event.PlayerFlagChangedEvent;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * FlagService implementation. Player flags are read into a player component on login and cached;
 * writes go to the DB immediately. Server flags are all read at startup and, when changed,
 * announced to other servers on the harbor:flags channel.
 */
public final class FlagManager implements FlagService {

    /** Server flag change notice. A null value means cleared. */
    public record ServerFlagMessage(String flag, @Nullable String value) {}

    static final Channel<ServerFlagMessage> CHANNEL =
            new Channel<>("harbor:flags", ServerFlagMessage.class);

    /**
     * Player flag cache (immutable map). Writes are immediate, so the component's save function
     * does nothing.
     */
    private final PlayerComponent<Map<String, String>> component;

    private final Database db;
    private final MessageBus bus;
    private final PlayerManager players;
    private final Tasks tasks;
    private final SeasonId season;
    private final Map<String, String> serverFlags = new ConcurrentHashMap<>();

    public FlagManager(
            Database db, MessageBus bus, PlayerManager players, Tasks tasks, SeasonId season) {
        this.db = db;
        this.bus = bus;
        this.players = players;
        this.tasks = tasks;
        this.season = season;
        this.component =
                new PlayerComponent<>("flags", this::loadPlayer, (c, uuid, s, value) -> {});
    }

    /**
     * Startup: register the component, load server flags (read on a virtual thread and awaited
     * since this is startup), subscribe to changes from other servers.
     */
    public void start() throws SQLException {
        players.register(component);
        try {
            serverFlags.putAll(db.async(this::loadServer).join());
        } catch (CompletionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw e;
        }
        bus.subscribe(
                CHANNEL,
                envelope ->
                        tasks.main()
                                .execute(
                                        () ->
                                                applyServer(
                                                        envelope.payload().flag(),
                                                        envelope.payload().value())));
    }

    // ------------------------------------------------------------------ Player

    @Override
    public boolean hasPlayer(UUID player, String flag) {
        return playerValue(player, flag).isPresent();
    }

    @Override
    public Optional<String> playerValue(UUID player, String flag) {
        return players.get(player).flatMap(p -> Optional.ofNullable(p.get(component).get(flag)));
    }

    @Override
    public CompletableFuture<Void> setPlayer(UUID player, Scope scope, String flag, String value) {
        FlagService.requireName(flag);
        int flagSeason = scope == Scope.PERM ? 0 : season.value();
        // On the main thread, update the cache first: while waiting for the DB write hasPlayer was
        // false, so a reward guarded by "only if the flag is absent" could be
        // claimed twice by rapid clicks. Revert if the write fails
        boolean optimistic = Bukkit.isPrimaryThread();
        Optional<String> before = optimistic ? playerValue(player, flag) : Optional.empty();
        if (optimistic) {
            updatePlayer(player, flag, value);
        }
        return db.asyncVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "INSERT INTO player_flags (uuid, season, flag, value) VALUES (?, ?, ?, ?)"
                                                    + " ON DUPLICATE KEY UPDATE value = VALUES(value), set_at = CURRENT_TIMESTAMP(3)")) {
                                ps.setString(1, player.toString());
                                ps.setInt(2, flagSeason);
                                ps.setString(3, flag);
                                ps.setString(4, value);
                                ps.executeUpdate();
                            }
                        })
                .handleAsync(
                        (v, error) -> {
                            if (error != null) {
                                if (optimistic) {
                                    updatePlayer(player, flag, before.orElse(null));
                                }
                                throw error instanceof CompletionException ce
                                        ? ce
                                        : new CompletionException(error);
                            }
                            if (!optimistic) {
                                updatePlayer(player, flag, value);
                            }
                            return (Void) null;
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<Void> unsetPlayer(UUID player, String flag) {
        FlagService.requireName(flag);
        return db.asyncVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "DELETE FROM player_flags WHERE uuid = ? AND flag = ? AND season IN (0, ?)")) {
                                ps.setString(1, player.toString());
                                ps.setString(2, flag);
                                ps.setInt(3, season.value());
                                ps.executeUpdate();
                            }
                        })
                .thenRunAsync(() -> updatePlayer(player, flag, null), tasks.main());
    }

    private void updatePlayer(UUID player, String flag, @Nullable String value) {
        players.get(player)
                .ifPresent(
                        p -> {
                            Map<String, String> next = new HashMap<>(p.get(component));
                            if (value == null) {
                                next.remove(flag);
                            } else {
                                next.put(flag, value);
                            }
                            p.set(component, Map.copyOf(next));
                        });
        Bukkit.getPluginManager().callEvent(new PlayerFlagChangedEvent(player, flag, value));
    }

    private Map<String, String> loadPlayer(Connection c, UUID player, SeasonId s)
            throws SQLException {
        Map<String, String> flags = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT flag, value FROM player_flags WHERE uuid = ? AND season IN (0, ?)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, s.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    flags.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return Map.copyOf(flags);
    }

    // ------------------------------------------------------------------ Server

    @Override
    public boolean hasServer(String flag) {
        return serverFlags.containsKey(flag);
    }

    @Override
    public Optional<String> serverValue(String flag) {
        return Optional.ofNullable(serverFlags.get(flag));
    }

    @Override
    public CompletableFuture<Void> setServer(String flag, String value, boolean seasonal) {
        FlagService.requireName(flag);
        int flagSeason = seasonal ? season.value() : 0;
        return db.asyncVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "INSERT INTO server_flags (flag, value, season) VALUES (?, ?, ?)"
                                                    + " ON DUPLICATE KEY UPDATE value = VALUES(value), season = VALUES(season),"
                                                    + " set_at = CURRENT_TIMESTAMP(3)")) {
                                ps.setString(1, flag);
                                ps.setString(2, value);
                                ps.setInt(3, flagSeason);
                                ps.executeUpdate();
                            }
                        })
                .thenRunAsync(
                        () -> {
                            applyServer(flag, value);
                            bus.publish(CHANNEL, new ServerFlagMessage(flag, value));
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<Void> unsetServer(String flag) {
        return db.asyncVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement("DELETE FROM server_flags WHERE flag = ?")) {
                                ps.setString(1, flag);
                                ps.executeUpdate();
                            }
                        })
                .thenRunAsync(
                        () -> {
                            applyServer(flag, null);
                            bus.publish(CHANNEL, new ServerFlagMessage(flag, null));
                        },
                        tasks.main());
    }

    private void applyServer(String flag, @Nullable String value) {
        @Nullable String previous =
                value == null ? serverFlags.remove(flag) : serverFlags.put(flag, value);
        if (!Objects.equals(previous, value)) {
            Bukkit.getPluginManager().callEvent(new ServerFlagChangedEvent(flag, value));
        }
    }

    private Map<String, String> loadServer(Connection c) throws SQLException {
        Map<String, String> flags = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT flag, value FROM server_flags WHERE season IN (0, ?)")) {
            ps.setInt(1, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    flags.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return flags;
    }
}
