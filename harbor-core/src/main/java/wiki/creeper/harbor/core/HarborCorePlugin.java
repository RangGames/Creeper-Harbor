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
import wiki.creeper.harbor.common.yaml.YamlDefaults;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.ModuleHost;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.border.WorldBorderManager;
import wiki.creeper.harbor.core.chat.ChatBridge;
import wiki.creeper.harbor.core.chat.PlaceholderApiHook;
import wiki.creeper.harbor.core.command.HarborCommand;
import wiki.creeper.harbor.core.command.HelpCommand;
import wiki.creeper.harbor.core.content.DefaultContent;
import wiki.creeper.harbor.core.content.HarborContent;
import wiki.creeper.harbor.core.currency.CurrencyManager;
import wiki.creeper.harbor.core.currency.GoldCommand;
import wiki.creeper.harbor.core.currency.SeasonBalanceStore;
import wiki.creeper.harbor.core.flag.FlagManager;
import wiki.creeper.harbor.core.furniture.BlockBreakSpeed;
import wiki.creeper.harbor.core.furniture.CustomBlockManager;
import wiki.creeper.harbor.core.furniture.FurnishingCommand;
import wiki.creeper.harbor.core.furniture.FurnishingService;
import wiki.creeper.harbor.core.furniture.FurnitureManager;
import wiki.creeper.harbor.core.furniture.GlitchedNoteBlocks;
import wiki.creeper.harbor.core.gui.MenuListener;
import wiki.creeper.harbor.core.inventory.InventoryCommand;
import wiki.creeper.harbor.core.inventory.InventorySync;
import wiki.creeper.harbor.core.item.ItemManager;
import wiki.creeper.harbor.core.item.ItemModelSync;
import wiki.creeper.harbor.core.ledger.LedgerAuditJob;
import wiki.creeper.harbor.core.message.DialogManager;
import wiki.creeper.harbor.core.message.MessageManager;
import wiki.creeper.harbor.core.message.UiManager;
import wiki.creeper.harbor.core.modifier.ModifierManager;
import wiki.creeper.harbor.core.pack.LobbyMusic;
import wiki.creeper.harbor.core.pack.PackCommand;
import wiki.creeper.harbor.core.pack.PackManager;
import wiki.creeper.harbor.core.pack.PackRefresh;
import wiki.creeper.harbor.core.pack.ResourcePacks;
import wiki.creeper.harbor.core.pack.SoundCues;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.proxy.AfkWatcher;
import wiki.creeper.harbor.core.proxy.NetworkEvents;
import wiki.creeper.harbor.core.proxy.ProxyLink;
import wiki.creeper.harbor.core.rig.FishDemoCommand;
import wiki.creeper.harbor.core.rig.RigCommand;
import wiki.creeper.harbor.core.rig.RigManager;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.core.waypoint.WaypointManager;

/**
 * Harbor-Core startup order ('16. Plugin design' 5.1).
 *
 * <ol>
 *   <li>PluginLoader: load external libraries ({@link HarborCoreLoader})
 *   <li>Bootstrap: register datapack registry entries ({@link HarborCoreBootstrap})
 *   <li>onEnable: infra config → DB connection and migrations → Redis connection → content load
 *       (abort server startup on failure) → service registration → command registration
 *   <li>Module wiring: other modules register their parts through HarborModule
 *   <li>onDisable: block new logins → save online players' data → drain the write-behind queue →
 *       close connections
 * </ol>
 *
 * The shared folder is plugins/Harbor/ (harbor-infra.yml, content/). Missing ones are created from
 * the defaults in the jar.
 */
public final class HarborCorePlugin extends JavaPlugin implements Listener {

    private @Nullable Database db;
    private @Nullable Redis redis;
    private @Nullable MessageBus bus;
    private @Nullable WriteBehindQueue queue;
    private @Nullable PlayerManager players;
    private @Nullable MessageManager messages;
    private @Nullable RigManager rigs;
    private @Nullable WaypointManager waypoints;
    private @Nullable FurnitureManager furniture;
    private @Nullable BlockBreakSpeed breakSpeeds;

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
        } else {
            // Fill in settings added in newer versions (server values and comments are kept)
            try (InputStream in = getResource("harbor-infra.yml")) {
                YamlDefaults.Result filled = YamlDefaults.fillFile(infraFile, in);
                if (filled.changed()) {
                    log.warn(
                            "harbor-infra.yml에 새 설정 항목을 추가했습니다 (기본값, 원본은 .bak): {}",
                            filled.added());
                }
            }
        }

        // 1. Infra config
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

        // 2. DB connection and migrations (dependency order: common → core → each module)
        Database database = Database.connect(infra.database(), Bukkit::isPrimaryThread, dev);
        db = database;
        Migrations.migrate(database.dataSource(), "common", getClassLoader());
        Migrations.migrate(database.dataSource(), "core", getClassLoader());

        // 3. Redis
        Redis r = Redis.connect(infra.redis());
        redis = r;
        MessageBus messageBus = new MessageBus(r, infra.serverId());
        bus = messageBus;

        // 4. Content load (abort startup on failure)
        Tasks tasks = new Tasks(this);
        Path configuredContent = infra.contentDir();
        Path contentRoot =
                configuredContent != null ? configuredContent : harborDir.resolve("content");
        if (configuredContent == null) {
            // For the default folder, sync with the jar's default content: create missing files,
            // update unmodified ones, keep modified ones (a custom folder is left untouched)
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
            synced.filled()
                    .forEach(
                            (file, keys) ->
                                    log.info(
                                            "고친 콘텐츠 {}에 새 항목 {}개를 추가했습니다: {}",
                                            file,
                                            keys.size(),
                                            keys));
        }
        HarborContent content = new HarborContent(contentRoot, tasks, log);
        Result<ContentSnapshot, ValidationErrors> contentResult = content.loadBlocking();
        if (contentResult
                instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors)) {
            log.error("콘텐츠 오류 {}건 ({}):\n{}", errors.size(), contentRoot, errors.format());
            Bukkit.shutdown();
            return;
        }

        // 5. Services
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
        playerManager.setHoldTimeout(
                () ->
                        content.snapshot()
                                .single(Balance.TYPE)
                                .integer("session.hold_timeout_ms", 3000));
        dialogs.setFrozen(playerManager::frozen);
        FlagManager flags =
                new FlagManager(
                        database,
                        messageBus,
                        playerManager,
                        tasks,
                        infra.season(),
                        infra.serverId());
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
                new CustomBlockManager(content::snapshot, msg, items, tasks, log);
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
                        adminLog,
                        infra,
                        time,
                        database,
                        writeBehind,
                        messageBus,
                        locks,
                        r);
        Harbor.install(api);
        Bukkit.getServicesManager()
                .register(
                        wiki.creeper.harbor.core.api.ExternalEvents.class,
                        new wiki.creeper.harbor.core.event.CoreExternalEvents(
                                messageBus, infra.serverId(), log),
                        this,
                        org.bukkit.plugin.ServicePriority.Normal);
        WaypointManager waypointManager = new WaypointManager(this, content::snapshot);
        waypoints = waypointManager;
        Bukkit.getServicesManager()
                .register(
                        wiki.creeper.harbor.core.api.WaypointService.class,
                        waypointManager,
                        this,
                        org.bukkit.plugin.ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(waypointManager, this);
        ModuleHost.install(new ModuleRegistry(api, content, playerManager, command, log));

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(playerManager, this);
        pm.registerEvents(playerManager.freezeListener(), this);
        InventorySync inventorySync =
                new InventorySync(
                        playerManager,
                        database,
                        writeBehind,
                        content::snapshot,
                        msg,
                        tasks,
                        log,
                        infra.serverId(),
                        infra.role());
        inventorySync.register();
        pm.registerEvents(inventorySync, this);
        command.addModuleCommand(
                new InventoryCommand(
                                inventorySync,
                                playerManager,
                                r,
                                infra.serverId(),
                                msg,
                                tasks,
                                adminLog)
                        .build());
        pm.registerEvents(modifiers, this);
        pm.registerEvents(borders, this);
        pm.registerEvents(rigManager, this);
        pm.registerEvents(furnitureManager, this);
        pm.registerEvents(customBlocks, this);
        BlockBreakSpeed breakSpeed = new BlockBreakSpeed(customBlocks);
        breakSpeeds = breakSpeed;
        pm.registerEvents(breakSpeed, this);
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
        NetworkEvents.subscribe(messageBus, tasks);
        pm.registerEvents(
                new ChatBridge(
                        infra,
                        r,
                        messageBus,
                        content::snapshot,
                        ui,
                        new PlaceholderApiHook(log),
                        tasks),
                this);
        pm.registerEvents(this, this);
        PackManager packManager =
                new PackManager(infra, harborDir, content::snapshot, r, messageBus, tasks, log);
        command.addModuleCommand(new PackCommand(packManager, msg, adminLog).build());
        if (infra.role() == ServerRole.LOBBY) {
            pm.registerEvents(
                    new ResourcePacks(infra, packManager, messageBus, msg, dialogs, r, tasks),
                    this);
            LobbyMusic music = new LobbyMusic(content::snapshot);
            pm.registerEvents(music, this);
            music.start(tasks);
        } else {
            // Other servers only push pack updates to players already there
            var _ = new PackRefresh(packManager, msg);
        }
        if (infra.role() == ServerRole.MAIN) {
            AfkWatcher afk = new AfkWatcher(content::snapshot, proxy, msg, tasks);
            pm.registerEvents(afk, this);
            afk.start();
        }

        // 6. Commands (fired after all modules are enabled, so module subcommands are included)
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
        // Clean up rig entities → block new logins → save online players → drain the queue → close
        // connections
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
        if (waypoints != null) {
            waypoints.shutdown();
        }
        if (breakSpeeds != null) {
            breakSpeeds.shutdown();
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
