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
 * 점검 모드 (docs 20 1.4 MaintenanceService). 상태는 Redis 키 harbor:maintenance(JSON)에 두어 프록시를 재시작해도 유지된다.
 * Season 모듈의 시즌 전환도 같은 키를 쓴다.
 */
final class MaintenanceService {

    static final String KEY = "harbor:maintenance";
    private static final List<Long> COUNTDOWN = List.of(600L, 300L, 60L, 10L);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /** 점검 상태. until은 epoch 밀리초 (0이면 미정). */
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

    /** 시작할 때와 주기적으로 Redis에서 다시 읽는다. Redis가 없으면 설정 파일의 fallback 값을 쓴다. */
    void refresh() {
        try {
            String raw = redis.sync().get(KEY);
            state = raw == null ? State.OFF : Json.GSON.fromJson(raw, State.class);
        } catch (RuntimeException e) {
            if (config.maintenanceFallback()) {
                state = new State(true, "maintenance", 0, List.of());
            }
            log.warn("점검 상태를 읽지 못했습니다: {}", e.getMessage());
        }
    }

    State state() {
        return state;
    }

    /** 점검이 켜지면 바로 새 접속을 막는다 (기존 접속자는 카운트다운이 끝날 때 내보낸다). */
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

    /** 1초마다: 남은 시간 공지, 0초에 저장 신호 → 3초 뒤 bypass 외 전원 퇴장. */
    void tick() {
        State s = state;
        if (!s.on() || kicked) {
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
