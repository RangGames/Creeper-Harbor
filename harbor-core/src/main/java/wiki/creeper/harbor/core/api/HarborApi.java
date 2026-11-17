package wiki.creeper.harbor.core.api;

import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.time.HarborTime;

/** Core가 다른 모듈에 제공하는 서비스 묶음. */
public interface HarborApi {

    ContentService content();

    PlayerService players();

    ItemService items();

    CurrencyService currency();

    ModifierService modifiers();

    MessageService messages();

    FlagService flags();

    Ui ui();

    Dialogs dialogs();

    ProxyBridge proxy();

    InfraConfig infra();

    HarborTime time();

    Database database();

    WriteBehindQueue writeBehind();

    MessageBus bus();

    DistributedLock locks();
}
