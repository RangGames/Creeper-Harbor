package wiki.creeper.harbor.core.api;

import java.util.Optional;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.time.HarborTime;

/** The set of services Core provides to other modules. */
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

    /** Per-player world border arbitration. */
    WorldBorderService worldBorders();

    /** Display rig playback ('16. Plugin design' 5.10). */
    RigService rigs();

    /** Furniture and custom blocks (proposed). */
    FurnitureService furniture();

    InfraConfig infra();

    HarborTime time();

    Database database();

    WriteBehindQueue writeBehind();

    MessageBus bus();

    DistributedLock locks();

    /**
     * Redis key/value access (caches, instance assignment, reconnect keys). This is I/O, so don't
     * call it on the main thread.
     */
    Redis redis();

    /**
     * Services registered by other modules (Bukkit ServicesManager). Services of
     * optional-dependency modules may be absent, hence Optional. Look them up after the module is
     * enabled (after the other module's enable).
     */
    default <T> Optional<T> service(Class<T> type) {
        return Optional.ofNullable(Bukkit.getServicesManager().load(type));
    }

    /**
     * Publishes external integration events (webhooks, REST, Harbor-Web). Always present while Core
     * is enabled.
     */
    default ExternalEvents events() {
        return service(ExternalEvents.class).orElse(ExternalEvents.NOOP);
    }

    /** Mailbox (implemented by Economy). If absent, the caller defers. */
    default Optional<MailService> mail() {
        return service(MailService.class);
    }

    /** Parties (implemented by Boss). If absent, everyone is solo. */
    default Optional<PartyService> parties() {
        return service(PartyService.class);
    }
}
