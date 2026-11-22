package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.SaveAll;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.util.Json;

/**
 * Maintenance mode (docs 20 1.4 MaintenanceService). State lives in the Redis key
 * harbor:maintenance (JSON), so it survives proxy restarts. The Season module's season transition
 * uses the same key.
 */
final class MaintenanceService {

    static final String KEY = "harbor:maintenance";
    private static final List<Long> COUNTDOWN = List.of(600L, 300L, 60L, 10L);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /** Maintenance state. until is epoch milliseconds (0 means undetermined). */
    record State(boolean on, String reason, long until, List<UUID> bypass) {
        static final State OFF = new State(false, "", 0, List.of());
    }

    private final Object plugin;
    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final Redis redis;
    private final MessageBus bus;
    private final ProxyText text;
    private final Logger log;
    private volatile State state = State.OFF;
    private volatile boolean kicked;

    /**
     * Whether Redis has been read at least once. On failures after that, the last state is kept.
     */
    private volatile boolean known;

    MaintenanceService(
            Object plugin,
            ProxyServer proxy,
            ProxyConfig config,
            Redis redis,
            MessageBus bus,
            ProxyText text,
            Logger log) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.redis = redis;
        this.bus = bus;
        this.text = text;
        this.log = log;
    }

    /**
     * Re-reads from Redis at startup and periodically. Without Redis at startup, uses the fallback
     * value from the config file (end time undetermined, so it only blocks new connections and does
     * not kick anyone). After a successful read, temporary failures keep the last state so players
     * are not kicked.
     */
    void refresh() {
        State next;
        try {
            String raw = redis.sync().get(KEY);
            next = raw == null ? State.OFF : Json.GSON.fromJson(raw, State.class);
            known = true;
        } catch (RuntimeException e) {
            log.warn("점검 상태를 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
            if (known || !config.maintenanceFallback()) {
                return;
            }
            next = new State(true, "maintenance", 0, List.of());
        }
        // Maintenance changed elsewhere (other proxy commands, Season) also gets the countdown and
        // kick again
        if (!next.equals(state)) {
            kicked = false;
        }
        state = next;
    }

    State state() {
        return state;
    }

    /**
     * Blocks new connections as soon as maintenance turns on (existing players are kicked when the
     * countdown ends).
     */
    boolean blocks(@Nullable UUID player) {
        State s = state;
        if (!s.on()) {
            return false;
        }
        return player == null
                || !(config.maintenanceBypass().contains(player) || s.bypass().contains(player));
    }

    Component kickMessage() {
        State s = state;
        return text.get(
                "maintenance.kick",
                Placeholder.unparsed("reason", s.reason()),
                Placeholder.unparsed(
                        "until",
                        s.until() == 0 ? "-" : TIME.format(Instant.ofEpochMilli(s.until()))));
    }

    void turnOn(int minutes, String reason) {
        State next =
                new State(
                        true,
                        reason,
                        System.currentTimeMillis() + minutes * 60_000L,
                        List.copyOf(config.maintenanceBypass()));
        redis.sync().set(KEY, Json.GSON.toJson(next));
        state = next;
        kicked = false;
    }

    void turnOff() {
        redis.sync().del(KEY);
        state = State.OFF;
        kicked = false;
    }

    /**
     * Every second: announce the remaining time; at 0 seconds send the save signal → 3 seconds
     * later kick everyone except bypass.
     */
    void tick() {
        State s = state;
        // until 0 means the end time is undetermined (fallback, etc.): only block new connections,
        // no countdown or kick
        if (!s.on() || kicked || s.until() == 0) {
            return;
        }
        long left = (s.until() - System.currentTimeMillis()) / 1000;
        if (COUNTDOWN.contains(left)) {
            Component msg =
                    text.get(
                            "maintenance.countdown",
                            Placeholder.unparsed("seconds", String.valueOf(left)),
                            Placeholder.unparsed("reason", s.reason()));
            proxy.getAllPlayers().forEach(p -> p.sendMessage(msg));
        }
        if (left <= 0) {
            kicked = true;
            proxy.getAllPlayers().forEach(p -> p.sendMessage(text.get("maintenance.started")));
            proxy.getAllServers()
                    .forEach(
                            server ->
                                    bus.publish(
                                            ProxyMessages.toServer(
                                                    server.getServerInfo().getName(),
                                                    SaveAll.class),
                                            new SaveAll("maintenance")));
            var _ =
                    proxy.getScheduler()
                            .buildTask(plugin, this::kickAll)
                            .delay(3, TimeUnit.SECONDS)
                            .schedule();
        }
    }

    private void kickAll() {
        for (Player player : proxy.getAllPlayers()) {
            if (blocks(player.getUniqueId())) {
                player.disconnect(kickMessage());
            }
        }
    }

    Set<UUID> bypass() {
        return config.maintenanceBypass();
    }
}
