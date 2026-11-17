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
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.Migrations;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;

/**
 * Harbor-Proxy (docs 20 1장): 접속 진입점, 서버 이동 지휘, 서버 간 채팅·공지 중계. Common을 품고 (DB 풀 5개, Redis 연결 1개)
 * Paper API는 쓰지 않는다.
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

        ProxyText text = new ProxyText(config.messages());
        ServerDirectory directory = new ServerDirectory();
        MoveCoordinator mover =
                new MoveCoordinator(proxy, config, directory, messageBus, database, log);
        QueueManager queue = new QueueManager(proxy, config, directory, mover, text);
        MaintenanceService maintenance =
                new MaintenanceService(this, proxy, config, r, messageBus, text, log);
        ChatRelay chat = new ChatRelay(proxy, config, text);
        JoinLog joinLog = new JoinLog(database, config.ipHashSecret(), log);

        directory.start(messageBus);
        mover.start();
        queue.start(messageBus);
        chat.start(messageBus);
        maintenance.refresh();

        proxy.getEventManager()
                .register(
                        this,
                        new ConnectionListener(
                                proxy, config, text, maintenance, mover, queue, chat, joinLog));
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
                        this::adminLog)
                .register();

        var _ =
                proxy.getScheduler()
                        .buildTask(
                                this,
                                () -> {
                                    queue.tick();
                                    maintenance.tick();
                                })
                        .repeat(1, TimeUnit.SECONDS)
                        .schedule();
        var _ =
                proxy.getScheduler()
                        .buildTask(this, maintenance::refresh)
                        .repeat(10, TimeUnit.SECONDS)
                        .schedule();
        log.info(
                "Harbor-Proxy 준비 완료 (로비 {}, 본 서버 {})",
                config.lobby(),
                config.mainServers().keySet());
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
        }
        return file;
    }

    /** /hp 명령 기록 (admin_log, Common 소유 테이블). */
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
