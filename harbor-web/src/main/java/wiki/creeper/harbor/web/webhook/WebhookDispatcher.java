package wiki.creeper.harbor.web.webhook;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * 웹훅 발송 (config.yml webhooks). harbor:events(모든 서버)를 구독해서 대상마다 거르고 보낸다. 여러 서버에서 켜도 Redis 잠금 {@value
 * #LOCK}을 잡은 서버 하나만 보낸다 (잠금은 10초마다 연장, 30초 TTL).
 *
 * <ul>
 *   <li>json: 이벤트 전체를 POST. 헤더 X-Harbor-Event · X-Harbor-Id · X-Harbor-Timestamp, secret이 있으면
 *       X-Harbor-Signature
 *   <li>discord: messages_ko.yml {@code web.discord.<type>} 문구를 content로 (문구가 없는 type은 보내지 않는다).
 *       자리표시자는 player · server · type과 data의 모든 칸 (점은 밑줄로, 예: rewards.gold → rewards_gold)
 * </ul>
 *
 * 대상마다 대기열 하나와 가상 스레드 하나. 실패하면 1·5·15초 뒤 재시도, 429면 retry_after만큼 기다린다. 대기열이 차면 오래된 것부터 버리지 않고 새 것을
 * 버리며 경고를 남긴다.
 */
public final class WebhookDispatcher {

    static final String LOCK = "harbor:lock:webhook";

    private static final Gson GSON = new Gson();

    private record Delivery(WebhookTarget target, ExternalEvent event, String body) {}

    private final HarborApi api;
    private final List<WebhookTarget> targets;
    private final int retries;
    private final Duration timeout;
    private final Logger log;
    private final HttpClient http;
    private final List<BlockingQueue<Delivery>> queues = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private volatile DistributedLock.@Nullable Handle leader;
    private @Nullable Thread leaderThread;

    public WebhookDispatcher(
            HarborApi api,
            List<WebhookTarget> targets,
            int retries,
            int queueSize,
            Duration timeout,
            Logger log) {
        this.api = api;
        this.targets = List.copyOf(targets);
        this.retries = Math.max(0, retries);
        this.timeout = timeout;
        this.log = log;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        for (int i = 0; i < this.targets.size(); i++) {
            queues.add(new ArrayBlockingQueue<>(Math.max(10, queueSize)));
        }
    }

    public void start() {
        running = true;
        for (int i = 0; i < targets.size(); i++) {
            BlockingQueue<Delivery> queue = queues.get(i);
            workers.add(
                    Thread.ofVirtual()
                            .name("harbor-webhook-" + targets.get(i).name())
                            .start(() -> work(queue)));
        }
        leaderThread = Thread.ofVirtual().name("harbor-webhook-leader").start(this::lead);
        api.bus().subscribe(Channels.EVENTS, envelope -> onEvent(envelope.payload()));
    }

    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        Thread t = leaderThread;
        if (t != null) {
            t.interrupt();
        }
        DistributedLock.@Nullable Handle h = leader;
        if (h != null) {
            var _ = h.release();
        }
    }

    public boolean leader() {
        return leader != null;
    }

    public Map<String, Long> stats() {
        return Map.of("sent", sent.get(), "failed", failed.get(), "dropped", dropped.get());
    }

    public List<WebhookTarget> targets() {
        return targets;
    }

    /** 잠금 유지 (가상 스레드). */
    private void lead() {
        while (running) {
            try {
                DistributedLock.@Nullable Handle h = leader;
                if (h == null) {
                    leader = api.locks().tryAcquire(LOCK, Duration.ofSeconds(30)).orElse(null);
                    if (leader != null) {
                        log.info("웹훅 발송 서버가 되었습니다 ({})", api.infra().serverId());
                    }
                } else if (!h.extend(Duration.ofSeconds(30))) {
                    leader = null;
                    log.warn("웹훅 발송 잠금을 잃었습니다 (다른 서버가 보냅니다)");
                }
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                leader = null;
                log.warn("웹훅 잠금 확인 실패 (Redis): {}", String.valueOf(e));
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void onEvent(ExternalEvent event) {
        if (!running || leader == null) {
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            WebhookTarget target = targets.get(i);
            if (!WebhookRules.accepts(target, event)) {
                continue;
            }
            Optional<String> body = body(target, event);
            if (body.isEmpty()) {
                continue;
            }
            if (!queues.get(i).offer(new Delivery(target, event, body.get()))) {
                dropped.incrementAndGet();
                log.warn("웹훅 대기열이 가득 차서 버렸습니다: {} {}", target.name(), event.type());
            }
        }
    }

    /** 테스트 발송 (/harbor web test). */
    public boolean test(String targetName, ExternalEvent event) {
        for (int i = 0; i < targets.size(); i++) {
            WebhookTarget target = targets.get(i);
            if (target.name().equals(targetName)) {
                String body =
                        body(target, event)
                                .orElseGet(() -> discordBody("[Harbor] test " + event.type()));
                return queues.get(i).offer(new Delivery(target, event, body));
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 본문

    Optional<String> body(WebhookTarget target, ExternalEvent event) {
        return switch (target.format()) {
            case JSON -> Optional.of(GSON.toJson(event));
            case DISCORD -> discordText(event).map(WebhookDispatcher::discordBody);
        };
    }

    private Optional<String> discordText(ExternalEvent event) {
        String key = "web.discord." + event.type();
        var messages = api.messages();
        if (!messages.has(key)) {
            return Optional.empty();
        }
        List<TagResolver> resolvers = new ArrayList<>();
        String player = event.playerName() != null ? event.playerName() : event.player();
        resolvers.add(Placeholder.unparsed("player", player == null ? "-" : player));
        resolvers.add(Placeholder.unparsed("server", event.server()));
        resolvers.add(Placeholder.unparsed("type", event.type()));
        WebhookRules.flatten(event.data())
                .forEach(
                        (k, v) -> {
                            String name = k.replace('.', '_').replaceAll("[^a-z0-9_\\-]", "_");
                            if (!name.equals("player")
                                    && !name.equals("server")
                                    && !name.equals("type")) {
                                resolvers.add(Placeholder.unparsed(name, v));
                            }
                        });
        String text =
                PlainTextComponentSerializer.plainText()
                        .serialize(messages.get(key, resolvers.toArray(TagResolver[]::new)));
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    static String discordBody(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("content", text.length() > 2000 ? text.substring(0, 1997) + "..." : text);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        o.add("allowed_mentions", mentions);
        return GSON.toJson(o);
    }

    // ------------------------------------------------------------------ 발송

    private void work(BlockingQueue<Delivery> queue) {
        while (running) {
            Delivery d;
            try {
                d = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (d == null) {
                continue;
            }
            if (!deliver(d)) {
                failed.incrementAndGet();
            } else {
                sent.incrementAndGet();
            }
        }
    }

    private boolean deliver(Delivery d) {
        for (int attempt = 0; attempt <= retries && running; attempt++) {
            try {
                HttpResponse<String> res =
                        http.send(request(d), HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code >= 200 && code < 300) {
                    return true;
                }
                long wait = WebhookRules.backoffMillis(attempt);
                if (code == 429) {
                    wait = retryAfter(res).orElse(wait);
                } else if (code >= 400 && code < 500) {
                    log.warn("웹훅 {} 거절 ({}): {}", d.target().name(), code, abbreviate(res.body()));
                    return false; // 요청이 틀렸으면 다시 보내도 같다
                }
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.io.IOException | RuntimeException e) {
                log.warn(
                        "웹훅 {} 발송 실패 ({}회): {}", d.target().name(), attempt + 1, String.valueOf(e));
                try {
                    Thread.sleep(WebhookRules.backoffMillis(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private HttpRequest request(Delivery d) {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(d.target().url()))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Harbor-Web")
                        .POST(HttpRequest.BodyPublishers.ofString(d.body()));
        if (d.target().format() == WebhookTarget.Format.JSON) {
            String ts = String.valueOf(Instant.now().getEpochSecond());
            b.header("X-Harbor-Event", d.event().type())
                    .header("X-Harbor-Id", d.event().id())
                    .header("X-Harbor-Timestamp", ts);
            if (!d.target().secret().isEmpty()) {
                b.header(
                        "X-Harbor-Signature",
                        WebhookRules.signature(d.target().secret(), ts, d.body()));
            }
        }
        return b.build();
    }

    private static Optional<Long> retryAfter(HttpResponse<String> res) {
        try {
            JsonObject o = GSON.fromJson(res.body(), JsonObject.class);
            if (o != null && o.has("retry_after")) {
                return Optional.of((long) Math.ceil(o.get("retry_after").getAsDouble() * 1000));
            }
        } catch (RuntimeException e) {
            // 헤더로
        }
        return res.headers()
                .firstValue("Retry-After")
                .flatMap(
                        v -> {
                            try {
                                return Optional.of(Long.parseLong(v.strip()) * 1000);
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        });
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
