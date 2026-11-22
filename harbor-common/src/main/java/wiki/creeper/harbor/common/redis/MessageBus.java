package wiki.creeper.harbor.common.redis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import wiki.creeper.harbor.common.util.Json;

/**
 * 형식이 있는 Redis 발행·구독.
 *
 * <p>한 Redis 채널에 여러 메시지 형식을 보낼 수 있다. 봉투의 type(레코드 이름)이 구독한 {@link Channel#type()}과 같을 때만 핸들러를 부른다.
 *
 * <p>핸들러는 Lettuce 이벤트 스레드에서 불린다. 게임 상태를 바꾸려면 핸들러 안에서 메인 스레드로 넘겨야 한다. 자기가 보낸 메시지도 받으므로 필요하면 {@link
 * Envelope#server()}로 거른다.
 */
public final class MessageBus implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("Harbor/MessageBus");

    /** 구독 실패 재시도 간격: 1초부터 두 배씩, 최대 30초. */
    private static final long RETRY_MIN_MS = 1_000;

    private static final long RETRY_MAX_MS = 30_000;

    private final Redis redis;
    private final String serverId;
    private final Map<String, List<Handler<?>>> handlers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private record Handler<T extends Record>(Channel<T> channel, Consumer<Envelope<T>> consumer) {
        void dispatch(Envelope<JsonElement> raw) {
            T payload = Json.GSON.fromJson(raw.payload(), channel.type());
            consumer.accept(new Envelope<>(raw.type(), raw.server(), raw.sentAt(), payload));
        }
    }

    public MessageBus(Redis redis, String serverId) {
        this.redis = redis;
        this.serverId = serverId;
        redis.pubSub()
                .addListener(
                        new RedisPubSubAdapter<>() {
                            @Override
                            public void message(String channel, String message) {
                                onMessage(channel, message);
                            }
                        });
        // Lettuce는 실제로 구독된 채널만 복구한다. 끊긴 동안 거부된 SUBSCRIBE까지 다시 건다 (SUBSCRIBE는 중복돼도 무해)
        redis.pubSub()
                .addListener(
                        new RedisConnectionStateListener() {
                            @Override
                            public void onRedisConnected(
                                    RedisChannelHandler<?, ?> connection, SocketAddress address) {
                                for (String name : handlers.keySet()) {
                                    subscribeLater(name, RETRY_MIN_MS);
                                }
                            }
                        });
    }

    /** 메시지를 보낸다. 실패하면 로그만 남기고 게임은 계속 진행한다. */
    public <T extends Record> void publish(Channel<T> channel, T payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", channel.type().getSimpleName());
        envelope.addProperty("server", serverId);
        envelope.addProperty("sentAt", Instant.now().toString());
        envelope.add("payload", Json.GSON.toJsonTree(payload));
        try {
            var _ =
                    redis.connection()
                            .async()
                            .publish(channel.name(), envelope.toString())
                            .exceptionally(
                                    e -> {
                                        LOG.log(
                                                Level.WARNING,
                                                "Redis 발행 실패 ("
                                                        + channel.name()
                                                        + "): "
                                                        + e.getMessage());
                                        return 0L;
                                    });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Redis 발행 실패 (" + channel.name() + "): " + e.getMessage());
        }
    }

    /** 구독한다. 같은 채널에 핸들러를 여러 개 둘 수 있다. */
    public <T extends Record> void subscribe(Channel<T> channel, Consumer<Envelope<T>> consumer) {
        List<Handler<?>> list =
                handlers.computeIfAbsent(
                        channel.name(),
                        name -> {
                            subscribeNow(name, RETRY_MIN_MS);
                            return new CopyOnWriteArrayList<>();
                        });
        list.add(new Handler<>(channel, consumer));
    }

    /** SUBSCRIBE를 보내고, 실패하면 간격을 늘려 다시 시도한다 (REJECT_COMMANDS라 끊긴 동안은 바로 실패한다). */
    private void subscribeNow(String name, long delayMs) {
        if (closed) {
            return;
        }
        CompletableFuture<Void> sent;
        try {
            sent = redis.pubSub().async().subscribe(name).toCompletableFuture();
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        var _ =
                sent.exceptionally(
                        e -> {
                            LOG.log(
                                    Level.WARNING,
                                    "Redis 구독 실패, "
                                            + delayMs / 1000
                                            + "초 뒤 다시 시도 ("
                                            + name
                                            + "): "
                                            + String.valueOf(e.getMessage()));
                            subscribeLater(name, delayMs);
                            return null;
                        });
    }

    private void subscribeLater(String name, long delayMs) {
        var _ =
                CompletableFuture.runAsync(
                        () -> subscribeNow(name, Math.min(delayMs * 2, RETRY_MAX_MS)),
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }

    private void onMessage(String channel, String message) {
        List<Handler<?>> list = handlers.get(channel);
        if (list == null) {
            return;
        }
        Envelope<JsonElement> raw;
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            raw =
                    new Envelope<>(
                            json.get("type").getAsString(),
                            json.get("server").getAsString(),
                            Instant.parse(json.get("sentAt").getAsString()),
                            json.get("payload"));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "잘못된 메시지 (" + channel + "): " + message);
            return;
        }
        for (Handler<?> handler : list) {
            if (!handler.channel().type().getSimpleName().equals(raw.type())) {
                continue;
            }
            try {
                handler.dispatch(raw);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "메시지 처리 중 오류 (" + channel + ")", e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (!handlers.isEmpty()) {
            var _ = redis.pubSub().async().unsubscribe(handlers.keySet().toArray(String[]::new));
        }
        handlers.clear();
    }
}
