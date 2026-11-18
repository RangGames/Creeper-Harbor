package wiki.creeper.harbor.core;

import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.time.HarborTime;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemService;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.ModifierService;
import wiki.creeper.harbor.core.api.PlayerService;
import wiki.creeper.harbor.core.api.ProxyBridge;
import wiki.creeper.harbor.core.api.RigService;
import wiki.creeper.harbor.core.api.Ui;
import wiki.creeper.harbor.core.api.WorldBorderService;

/** HarborApi 구현: 서비스 묶음. */
record CoreApi(
        ContentService content,
        PlayerService players,
        ItemService items,
        CurrencyService currency,
        ModifierService modifiers,
        MessageService messages,
        FlagService flags,
        Ui ui,
        Dialogs dialogs,
        ProxyBridge proxy,
        WorldBorderService worldBorders,
        RigService rigs,
        InfraConfig infra,
        HarborTime time,
        Database database,
        WriteBehindQueue writeBehind,
        MessageBus bus,
        DistributedLock locks,
        Redis redis)
        implements HarborApi {}
