package wiki.creeper.harbor.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.Migrations;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlDefaults;

/**
 * Harbor-Proxy (docs 20 chapter 1): connection entry point (maintenance, sanctions, donor tier,
 * session lock), main server queue, server/instance move orchestration, cross-server
 * chat/announcement relay, connection logging and multi-account detection, MOTD and server links.
 * Bundles Common (DB pool of 5, one Redis connection) and does not use the Paper API.
 */
@Plugin(
        id = "harbor-proxy",
        name = "Harbor-Proxy",
        version = "0.1.0-SNAPSHOT",
        description = "크리퍼 항구 프록시 (접속·이동·대기열·채팅)",
        url = "https://creeper.wiki")
public final class HarborProxyPlugin {

    private final ProxyServer proxy;
    private final Logger log;
    private final Path dataDir;
    private @Nullable Database db;
    private @Nullable Redis redis;
    private @Nullable MessageBus bus;

    @Inject
    public HarborProxyPlugin(ProxyServer proxy, Logger log, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.log = log;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            start();
        } catch (Exception e) {
            log.error("Harbor-Proxy 시작 실패. 프록시를 멈춥니다", e);
            proxy.shutdown();
        }
    }

    private void start() throws IOException {
        Path infraFile = saveDefault("harbor-infra.yml");
        Path proxyFile = saveDefault("harbor-proxy.yml");

        Result<InfraConfig, ValidationErrors> infraResult = InfraConfig.load(infraFile);
        if (!(infraResult instanceof Result.Ok<InfraConfig, ValidationErrors>(InfraConfig infra))) {
            throw new IOException(
                    "harbor-infra.yml 오류:\n"
                            + ((Result.Err<InfraConfig, ValidationErrors>) infraResult)
                                    .error()
                                    .format());
        }
        Result<ProxyConfig, ValidationErrors> configResult = ProxyConfig.load(proxyFile);
        if (!(configResult
                instanceof Result.Ok<ProxyConfig, ValidationErrors>(ProxyConfig config))) {
            throw new IOException(
                    "harbor-proxy.yml 오류:\n"
                            + ((Result.Err<ProxyConfig, ValidationErrors>) configResult)
                                    .error()
                                    .format());
        }
        if (config.ipHashSecret().equals("change-me")) {
            log.warn("harbor-proxy.yml의 ip_hash_secret을 바꾸세요 (접속 기록 IP 해시 키)");
        }

        Database database = Database.connect(infra.database(), () -> false, false);
        db = database;
        ClassLoader loader = getClass().getClassLoader();
        Migrations.migrate(database.dataSource(), "proxy", loader);

        Redis r = Redis.connect(infra.redis());
        redis = r;
        MessageBus messageBus = new MessageBus(r, infra.serverId());
        bus = messageBus;

        ProxyText text = new ProxyText(config.messages(), bundledMessages());
        ServerDirectory directory = new ServerDirectory(config);
        SeatReservations seats = new SeatReservations();
        SessionLocks locks = new SessionLocks(r);
        Executor io = database.executor();
        MoveCoordinator mover =
                new MoveCoordinator(
                        proxy, config, directory, messageBus, database, seats, r::isConnected, log);
        QueueManager queue =
                new QueueManager(proxy, config, directory, mover, seats, text, r::isConnected);
        InstanceRouter instances =
                new InstanceRouter(proxy, config, directory, mover, seats, messageBus, r, io, log);
        StaleLockWatcher lockWatcher =
                new StaleLockWatcher(proxy, locks, queue, config.lobby(), log);
        MaintenanceService maintenance =
                new MaintenanceService(this, proxy, config, r, messageBus, text, log);
        JoinLog joinLog = new JoinLog(database, config.ipHashSecret(), log);
        SanctionService sanctions = new SanctionService(proxy, database, text, joinLog, log);
        AltDetector alts = new AltDetector(proxy, database, config.alt(), text, log);
        ChatRelay chat = new ChatRelay(proxy, config, text, sanctions);

        directory.start(messageBus);
        mover.start();
        queue.start(messageBus, instances::tryRejoin);
        mover.onLobbyMove(
                new MoveCoordinator.LobbyHook() {
                    @Override
                    public void before(UUID player) {
                        queue.beforeLobbyMove(player);
                    }

                    @Override
                    public void after(UUID player, String origin, boolean ok) {
                        queue.afterLobbyMove(player, origin, ok);
                    }
                });
        instances.start();
        chat.start(messageBus);
        maintenance.refresh();

        proxy.getEventManager()
                .register(
                        this,
                        new ConnectionListener(
                                proxy,
                                config,
                                text,
                                maintenance,
                                mover,
                                queue,
                                chat,
                                joinLog,
                                sanctions,
                                alts,
                                lockWatcher,
                                seats,
                                r,
                                io,
                                log));
        proxy.getEventManager()
                .register(this, new ServerListPing(proxy, config, text, maintenance, queue));
        new ProxyCommands(
                        this,
                        proxy,
                        config,
                        text,
                        mover,
                        queue,
                        maintenance,
                        directory,
                        chat,
                        messageBus,
                        this::adminLog,
                        new SanctionCommands(
                                proxy,
                                config,
                                text,
                                sanctions,
                                alts,
                                joinLog,
                                r,
                                io,
                                this::adminLog))
                .register();

        var _ =
                proxy.getScheduler()
                        .buildTask(
                                this,
                                () -> {
                                    queue.tick();
                                    maintenance.tick();
                                    lockWatcher.tick();
                                })
                        .repeat(1, TimeUnit.SECONDS)
                        .schedule();
        var _ =
                proxy.getScheduler()
                        .buildTask(
                                this,
                                () -> {
                                    maintenance.refresh();
                                    directory.checkStale(
                                            Duration.ofSeconds(config.heartbeatAlertSeconds()),
                                            (server, seconds) -> {
                                                log.warn("하트비트 끊김: {} ({}초)", server, seconds);
                                                text.notifyStaff(
                                                        proxy,
                                                        ProxyPerms.SERVERS,
                                                        text.get(
                                                                "server.stale",
                                                                Placeholder.unparsed(
                                                                        "server", server),
                                                                Placeholder.unparsed(
                                                                        "seconds",
                                                                        String.valueOf(seconds))));
                                            });
                                })
                        .repeat(10, TimeUnit.SECONDS)
                        .schedule();
        var _ =
                proxy.getScheduler()
                        .buildTask(this, () -> joinLog.purge(config.joinLogRetentionDays()))
                        .delay(1, TimeUnit.MINUTES)
                        .repeat(1, TimeUnit.DAYS)
                        .schedule();
        log.info(
                "Harbor-Proxy 준비 완료 (로비 {}, 본 서버 {})",
                config.lobby(),
                config.mainServers().keySet());
    }

    /**
     * Text of the default harbor-proxy.yml in the jar (fills new keys missing from the server
     * config).
     */
    private Map<String, String> bundledMessages() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("harbor-proxy.yml")) {
            if (in == null) {
                return Map.of();
            }
            return ProxyConfig.defaultMessages(in);
        }
    }

    private Path saveDefault(String name) throws IOException {
        Path file = dataDir.resolve(name);
        if (Files.notExists(file)) {
            Files.createDirectories(dataDir);
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                if (in == null) {
                    throw new IOException("기본 " + name + "이 jar에 없습니다");
                }
                Files.copy(in, file);
            }
            log.warn("기본 설정을 만들었습니다: {}", file);
        } else {
            // Fill config entries added in the new version (server values and comments stay as they
            // are)
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                YamlDefaults.Result filled = YamlDefaults.fillFile(file, in);
                if (filled.changed()) {
                    log.warn("{}에 새 설정 항목을 추가했습니다 (기본값, 원본은 .bak): {}", name, filled.added());
                }
            }
        }
        return file;
    }

    /** /hp command log (admin_log, a Common-owned table). */
    private void adminLog(CommandSource admin, String action, String target, String detail) {
        Database database = db;
        if (database == null) {
            return;
        }
        String adminId =
                admin instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
        var _ =
                database.asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, adminId);
                                        ps.setString(2, "proxy." + action);
                                        ps.setString(3, target);
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.warn("admin_log 기록 실패: {}", action);
                                    return null;
                                });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (bus != null) {
            bus.close();
        }
        if (redis != null) {
            redis.close();
        }
        if (db != null) {
            db.close();
        }
    }
}
