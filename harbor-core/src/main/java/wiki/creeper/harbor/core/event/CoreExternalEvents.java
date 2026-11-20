package wiki.creeper.harbor.core.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.core.api.ExternalEvents;

/** {@link ExternalEvents} 구현: 값을 JSON에 맞게 다듬어 harbor:events로 발행한다. */
public final class CoreExternalEvents implements ExternalEvents {

    private static final Pattern TYPE = Pattern.compile(TYPE_PATTERN);
    private static final int MAX_DEPTH = 6;

    private final MessageBus bus;
    private final String serverId;
    private final Logger log;

    public CoreExternalEvents(MessageBus bus, String serverId, Logger log) {
        this.bus = bus;
        this.serverId = serverId;
        this.log = log;
    }

    @Override
    public void emit(
            String type, @Nullable UUID player, @Nullable String playerName, Map<String, ?> data) {
        if (!TYPE.matcher(type).matches()) {
            log.warn("외부 이벤트 type 형식이 틀렸습니다 (모듈.사건): {}", type);
            return;
        }
        try {
            bus.publish(
                    Channels.EVENTS,
                    new ExternalEvent(
                            UUID.randomUUID().toString(),
                            type,
                            1,
                            serverId,
                            Instant.now().toString(),
                            player == null ? null : player.toString(),
                            playerName,
                            normalize(data)));
        } catch (RuntimeException e) {
            log.warn("외부 이벤트 발행 실패 {}: {}", type, String.valueOf(e));
        }
    }

    /** JSON에 넣을 수 있는 값만 남긴다 (null 값은 뺀다). */
    public static Map<String, Object> normalize(Map<String, ?> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach(
                (k, v) -> {
                    Object n = value(v, 0);
                    if (n != null) {
                        out.put(k, n);
                    }
                });
        return out;
    }

    private static @Nullable Object value(@Nullable Object v, int depth) {
        if (v == null) {
            return null;
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (depth >= MAX_DEPTH) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach(
                    (k, x) -> {
                        Object n = value(x, depth + 1);
                        if (n != null) {
                            out.put(String.valueOf(k), n);
                        }
                    });
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object x : c) {
                Object n = value(x, depth + 1);
                if (n != null) {
                    out.add(n);
                }
            }
            return out;
        }
        if (v instanceof Enum<?> e) {
            return e.name().toLowerCase(java.util.Locale.ROOT);
        }
        return String.valueOf(v);
    }
}
