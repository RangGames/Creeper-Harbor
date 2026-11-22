package wiki.creeper.harbor.common.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;

/**
 * Lettuce connections. Opens one connection for commands and one for subscriptions.
 *
 * <p>On disconnect Lettuce reconnects automatically, and the subscription connection restores its
 * subscriptions after reconnecting. Meanwhile only announcement/event propagation stops; the game
 * continues. Session locks treat a missing Redis as failure and block server moves.
 */
public final class Redis implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSub;

    private Redis(
            RedisClient client,
            StatefulRedisConnection<String, String> connection,
            StatefulRedisPubSubConnection<String, String> pubSub) {
        this.client = client;
        this.connection = connection;
        this.pubSub = pubSub;
    }

    /** Connects. Throws on failure (aborting server startup). */
    public static Redis connect(RedisConfig config) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofSeconds(3));
        RedisClient client = RedisClient.create(uri);
        client.setOptions(
                ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build());
        try {
            return new Redis(client, client.connect(), client.connectPubSub());
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
    }

    /** Synchronous commands. Blocking, so do not use on the main thread. */
    public RedisCommands<String, String> sync() {
        return connection.sync();
    }

    public StatefulRedisConnection<String, String> connection() {
        return connection;
    }

    StatefulRedisPubSubConnection<String, String> pubSub() {
        return pubSub;
    }

    public boolean isConnected() {
        return connection.isOpen();
    }

    @Override
    public void close() {
        pubSub.close();
        connection.close();
        client.shutdown();
    }
}
