package wiki.creeper.harbor.common.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;

/**
 * Lettuce 연결. 명령용 연결 하나와 구독용 연결 하나를 연다.
 *
 * <p>연결이 끊기면 Lettuce가 자동으로 다시 연결하고, 구독 연결은 다시 연결된 뒤 구독을 복구한다. 그 사이 공지·이벤트 전파만 멈추고 게임은 계속 진행한다. 세션
 * 잠금은 Redis가 없으면 실패로 보고 서버 이동을 막는다.
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

    /** 연결한다. 실패하면 예외 (서버 시작 중단). */
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

    /** 동기 명령. 블로킹이므로 메인 스레드에서 쓰지 않는다. */
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
