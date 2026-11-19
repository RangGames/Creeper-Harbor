package wiki.creeper.harbor.core;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.Messages;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.Migrations;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.common.time.HarborTime;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.ModuleHost;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.border.WorldBorderManager;
import wiki.creeper.harbor.core.chat.ChatBridge;
import wiki.creeper.harbor.core.command.HarborCommand;
import wiki.creeper.harbor.core.command.HelpCommand;
import wiki.creeper.harbor.core.content.DefaultContent;
import wiki.creeper.harbor.core.content.HarborContent;
import wiki.creeper.harbor.core.currency.CurrencyManager;
import wiki.creeper.harbor.core.currency.GoldCommand;
import wiki.creeper.harbor.core.currency.SeasonBalanceStore;
import wiki.creeper.harbor.core.flag.FlagManager;
import wiki.creeper.harbor.core.furniture.CustomBlockManager;
import wiki.creeper.harbor.core.furniture.FurnishingCommand;
import wiki.creeper.harbor.core.furniture.FurnishingService;
import wiki.creeper.harbor.core.furniture.FurnitureManager;
import wiki.creeper.harbor.core.furniture.GlitchedNoteBlocks;
import wiki.creeper.harbor.core.gui.MenuListener;
import wiki.creeper.harbor.core.item.ItemManager;
import wiki.creeper.harbor.core.item.ItemModelSync;
import wiki.creeper.harbor.core.ledger.LedgerAuditJob;
import wiki.creeper.harbor.core.message.DialogManager;
import wiki.creeper.harbor.core.message.MessageManager;
import wiki.creeper.harbor.core.message.UiManager;
import wiki.creeper.harbor.core.modifier.ModifierManager;
import wiki.creeper.harbor.core.pack.PackCommand;
import wiki.creeper.harbor.core.pack.PackManager;
import wiki.creeper.harbor.core.pack.ResourcePacks;
import wiki.creeper.harbor.core.pack.SoundCues;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.proxy.ProxyLink;
import wiki.creeper.harbor.core.rig.FishDemoCommand;
import wiki.creeper.harbor.core.rig.RigCommand;
import wiki.creeper.harbor.core.rig.RigManager;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Harbor-Core 시작 순서 ('16. 플러그인 설계' 5.1).
 *
 * <ol>
 *   <li>PluginLoader: 외부 라이브러리 적재 ({@link HarborCoreLoader})
 *   <li>Bootstrap: 데이터팩 레지스트리 항목 등록 ({@link HarborCoreBootstrap})
 *   <li>onEnable: 인프라 설정 → DB 연결·마이그레이션 → Redis 연결 → 콘텐츠 로드(실패하면 서버 시작 중단) → 서비스 등록 → 명령어 등록
 *   <li>모듈 연결: 다른 모듈이 HarborModule로 자기 것을 등록
 *   <li>onDisable: 새 접속 차단 → 접속자 데이터 저장 → 지연 쓰기 큐 비우기 → 연결 종료
 * </ol>
 *
 * 공유 폴더는 plugins/Harbor/ (harbor-infra.yml, content/). 둘 다 없으면 jar에 든 기본값으로 만든다.
 */
public final class HarborCorePlugin extends JavaPlugin implements Listener {

    private @Nullable Database db;
    private @Nullable Redis redis;
    private @Nullable MessageBus bus;
    private @Nullable WriteBehindQueue queue;
    private @Nullable PlayerManager players;
    private @Nullable MessageManager messages;
    private @Nullable RigManager rigs;
    private @Nullable FurnitureManager furniture;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        try {
            start(log);
        } catch (Exception e) {
            log.error("Harbor-Core 시작 실패. 서버를 멈춥니다", e);
            Bukkit.shutdown();
        }
    }

    private void start(Logger log) throws IOException, SQLException {
        Path pluginsDir = getDataFolder().toPath().toAbsolutePath().getParent();
        if (pluginsDir == null) {
            throw new IOException("plugins 폴더를 찾지 못했습니다");
        }
        Path harborDir = pluginsDir.resolve("Harbor");
        Path infraFile = harborDir.resolve("harbor-infra.yml");
        if (Files.notExists(infraFile)) {
            Files.createDirectories(harborDir);
            try (InputStream in = getResource("harbor-infra.yml")) {
                if (in == null) {
                    throw new IOException("기본 harbor-infra.yml이 jar에 없습니다");
                }
                Files.copy(in, infraFile);
            }
            log.warn("기본 설정을 만들었습니다: {} (서버 ID·DB·Redis를 확인하세요)", infraFile);
        }

        // 1. 인프라 설정
        Result<InfraConfig, ValidationErrors> loaded = InfraConfig.load(infraFile);
        if (!(loaded instanceof Result.Ok<InfraConfig, ValidationErrors>(InfraConfig infra))) {
            log.error(
                    "harbor-infra.yml 오류:\n{}",
                    ((Result.Err<InfraConfig, ValidationErrors>) loaded).error().format());
            Bukkit.shutdown();
            return;
        }
        boolean dev = infra.dev() || Boolean.getBoolean("harbor.dev");
        log.info("서버 {} ({}) 시즌 {}", infra.serverId(), infra.role().key(), infra.season());

        // 2. DB 연결·마이그레이션 (의존 순서: common → core → 각 모듈)
        Database database = Database.connect(infra.database(), Bukkit::isPrimaryThread, dev);
        db = database;
        Migrations.migrate(database.dataSource(), "common", getClassLoader());
        Migrations.migrate(database.dataSource(), "core", getClassLoader());

        // 3. Redis
        Redis r = Redis.connect(infra.redis());
        redis = r;
        MessageBus messageBus = new MessageBus(r, infra.serverId());
        bus = messageBus;

        // 4. 콘텐츠 로드 (실패하면 시작 중단)
        Tasks tasks = new Tasks(this);
        Path configuredContent = infra.contentDir();
        Path contentRoot =
                configuredContent != null ? configuredContent : harborDir.resolve("content");
        if (configuredContent == null) {
            // 기본 폴더면 jar의 기본 콘텐츠에 맞춘다: 없는 파일 생성, 고치지 않은 파일 갱신, 고친 파일은 유지 (따로 지정한 폴더는 건드리지 않는다)
            DefaultContent.SyncResult synced = DefaultContent.sync(this::getResource, contentRoot);
            if (synced.created() + synced.updated() + synced.removed() > 0) {
                log.info(
                        "기본 콘텐츠 맞춤: 새로 {}개, 갱신 {}개, 삭제 {}개 ({})",
                        synced.created(),
                        synced.updated(),
                        synced.removed(),
                        contentRoot);
            }
            if (synced.backup() != null) {
                log.warn("기록이 없는 옛 콘텐츠 폴더라 바뀐 파일의 원본을 {}에 옮겨 두었습니다", synced.backup());
            }
            if (!synced.keptModified().isEmpty()) {
                log.warn(
                        "서버에서 고친 콘텐츠 파일 {}개는 새 기본값으로 바꾸지 않았습니다: {}",
                        synced.keptModified().size(),
                        synced.keptModified());
            }
        }
        HarborContent content = new HarborContent(contentRoot, tasks, log);
        Result<ContentSnapshot, ValidationErrors> contentResult = content.loadBlocking();
        if (contentResult
                instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors)) {
            log.error("콘텐츠 오류 {}건 ({}):\n{}", errors.size(), contentRoot, errors.format());
            Bukkit.shutdown();
            return;
        }

        // 5. 서비스
        HarborTime time = infra.time();
        UiManager ui = new UiManager();
        MessageManager msg =
                new MessageManager(() -> content.snapshot().single(Messages.TYPE), ui::glyphs, log);
        messages = msg;
        DialogManager dialogs = new DialogManager(tasks);
        WriteBehindQueue writeBehind = new WriteBehindQueue(database);
        queue = writeBehind;
        DistributedLock locks = new DistributedLock(r);
        PlayerManager playerManager =
                new PlayerManager(
                        infra,
                        database,
                        writeBehind,
                        new SessionLocks(r),
                        messageBus,
                        msg,
                        tasks,
                        log);
        players = playerManager;
        FlagManager flags =
                new FlagManager(database, messageBus, playerManager, tasks, infra.season());
        flags.start();
        ModifierManager modifiers =
                new ModifierManager(() -> content.snapshot().single(Balance.TYPE));
        SeasonBalanceStore balanceStore = new SeasonBalanceStore(infra.season());
        CurrencyManager currency =
                new CurrencyManager(database, balanceStore, playerManager, tasks, infra.season());
        currency.listen(messageBus, infra.serverId());
        ItemManager items =
                new ItemManager(
                        content::snapshot, msg, database, infra.season(), infra.zone(), log);
        ProxyLink proxy = new ProxyLink(infra, messageBus, tasks, playerManager::acceptingLogins);
        WorldBorderManager borders = new WorldBorderManager();
        RigManager rigManager = new RigManager(this, content::snapshot, tasks, log);
        rigs = rigManager;
        FurnitureManager furnitureManager = new FurnitureManager(content::snapshot, msg, ui, tasks);
        furniture = furnitureManager;
        CustomBlockManager customBlocks =
                new CustomBlockManager(content::snapshot, msg, tasks, log);
        AdminLog adminLog = new AdminLog(database, log);
        HarborCommand command =
                new HarborCommand(
                        content,
                        items,
                        modifiers,
                        playerManager,
                        currency,
                        msg,
                        database,
                        writeBehind,
                        adminLog);

        CoreApi api =
                new CoreApi(
                        content,
                        playerManager,
                        items,
                        currency,
                        modifiers,
                        msg,
                        flags,
                        ui,
                        dialogs,
                        proxy,
                        borders,
                        rigManager,
                        new FurnishingService(furnitureManager, customBlocks),
                        infra,
                        time,
                        database,
                        writeBehind,
                        messageBus,
                        locks,
                        r);
        Harbor.install(api);
        ModuleHost.install(new ModuleRegistry(api, content, playerManager, command, log));

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(playerManager, this);
        pm.registerEvents(modifiers, this);
        pm.registerEvents(borders, this);
        pm.registerEvents(rigManager, this);
        pm.registerEvents(furnitureManager, this);
        pm.registerEvents(customBlocks, this);
        GlitchedNoteBlocks glitched = new GlitchedNoteBlocks(content::snapshot, tasks, log);
        pm.registerEvents(glitched, this);
        FurnishingCommand furnishing =
                new FurnishingCommand(furnitureManager, customBlocks, msg, adminLog);
        command.addModuleCommand(furnishing.furniture());
        command.addModuleCommand(furnishing.blocks());
        pm.registerEvents(new ItemModelSync(items), this);
        command.addModuleCommand(new RigCommand(rigManager, msg, adminLog).build());
        command.addModuleCommand(
                new FishDemoCommand(rigManager, api.content(), items, playerManager, msg, adminLog)
                        .build());
        HelpCommand help = new HelpCommand(this, api.content(), msg, flags);
        pm.registerEvents(help, this);
        GoldCommand gold = new GoldCommand(currency, playerManager, msg, adminLog);
        pm.registerEvents(new MenuListener(), this);
        SoundCues soundCues = new SoundCues(api.content());
        pm.registerEvents(soundCues, this);
        soundCues.subscribe(messageBus, tasks);
        pm.registerEvents(new ChatBridge(infra, r, messageBus), this);
        pm.registerEvents(this, this);
        PackManager packManager =
                new PackManager(infra, harborDir, content::snapshot, r, messageBus, tasks, log);
        command.addModuleCommand(new PackCommand(packManager, msg, adminLog).build());
        if (infra.role() == ServerRole.LOBBY) {
            pm.registerEvents(
                    new ResourcePacks(infra, packManager, messageBus, msg, dialogs), this);
        }

        // 6. 명령어 (모든 모듈이 켜진 뒤 발생하므로 모듈 하위 명령도 들어간다)
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            command.register(event.registrar());
                            help.register(event.registrar());
                            gold.register(event.registrar());
                        });

        playerManager.start();
        proxy.start();
        rigManager.start();
        customBlocks.start();
        glitched.start();
        packManager.start();
        new LedgerAuditJob(database, balanceStore, locks, time, msg, tasks, log).schedule();
        log.info("Harbor-Core 준비 완료: 콘텐츠 {}", content.snapshot().counts());
    }

    @EventHandler
    public void onContentReloaded(ContentReloadedEvent event) {
        MessageManager msg = messages;
        if (msg != null) {
            msg.clearWarnings();
        }
    }

    @Override
    public void onDisable() {
        Logger log = getSLF4JLogger();
        // 리그 엔티티 정리 → 새 접속 차단 → 접속자 저장 → 큐 비우기 → 연결 종료
        if (rigs != null) {
            try {
                rigs.shutdown();
            } catch (RuntimeException e) {
                log.error("리그 정리 중 오류", e);
            }
        }
        if (furniture != null) {
            furniture.shutdown();
        }
        try {
            if (players != null) {
                players.shutdown();
            }
        } catch (RuntimeException e) {
            log.error("접속자 저장 중 오류", e);
        }
        if (queue != null) {
            queue.close();
        }
        if (bus != null) {
            bus.close();
        }
        if (redis != null) {
            redis.close();
        }
        if (db != null) {
            db.close();
        }
        Harbor.install(null);
        ModuleHost.install(null);
    }
}
