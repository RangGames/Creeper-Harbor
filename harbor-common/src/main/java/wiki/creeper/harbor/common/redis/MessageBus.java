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
 * Typed Redis publish/subscribe.
 *
 * <p>Several message types can be sent on one Redis channel. A handler is called only when the
 * envelope type (record name) equals the subscribed {@link Channel#type()}.
 *
 * <p>Handlers are called on the Lettuce event thread. To change game state, hand off to the main
 * thread inside the handler. Own messages are received too, so filter with {@link
 * Envelope#server()} if needed.
 */
public final class MessageBus implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("Harbor/MessageBus");

    /** Subscription failure retry interval: from 1 second, doubling, up to 30 seconds. */
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
        // Lettuce only restores channels that were actually subscribed. Also resend SUBSCRIBEs
        // rejected while disconnected (duplicate SUBSCRIBE is harmless)
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

    /** Sends a message. On failure only logs, and the game continues. */
    public <T extends Record> void publish(Channel<T> channel, T payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", channel.type().getSimpleName());
        envelope.addProperty("server", serverId);
        envelope.addProperty("sent_at", Instant.now().toString());
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

    /** Subscribes. Several handlers may share a channel. */
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

    /**
     * Sends SUBSCRIBE and, on failure, retries with growing intervals (REJECT_COMMANDS makes it
     * fail immediately while disconnected).
     */
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
                            // sentAt: envelopes from servers on the previous version
                            Instant.parse(
                                    json.get(json.has("sent_at") ? "sent_at" : "sentAt")
                                            .getAsString()),
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
