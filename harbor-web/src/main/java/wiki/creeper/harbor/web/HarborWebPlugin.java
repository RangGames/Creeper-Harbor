package wiki.creeper.harbor.web;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.web.http.ApiServer;
import wiki.creeper.harbor.web.http.Calls;
import wiki.creeper.harbor.web.http.CoreRoutes;
import wiki.creeper.harbor.web.http.ModuleRoutes;
import wiki.creeper.harbor.web.http.NetworkOnline;
import wiki.creeper.harbor.web.http.RecentEvents;
import wiki.creeper.harbor.web.http.Router;
import wiki.creeper.harbor.web.relay.EventRelay;
import wiki.creeper.harbor.web.relay.EventSerializer;
import wiki.creeper.harbor.web.webhook.WebhookDispatcher;
import wiki.creeper.harbor.web.webhook.WebhookTarget;

/**
 * Harbor-Web (proposed): external integration.
 *
 * <ul>
 *   <li>Event publishing (every server): game module events → Redis harbor:events ({@link
 *       EventRelay})
 *   <li>Webhooks (one server): harbor:events → Discord/JSON POST ({@link WebhookDispatcher})
 *   <li>REST API (one server): read-only queries ({@link ApiServer})
 * </ul>
 *
 * Settings live in plugins/Harbor-Web/config.yml (not the repository content/, since it holds
 * secrets). Admin: /harbor web status · test · events.
 */
public final class HarborWebPlugin extends HarborModule {

    private @Nullable EventRelay relay;
    private @Nullable WebhookDispatcher webhooks;
    private @Nullable ApiServer http;
    private @Nullable RecentEvents recent;

    @Override
    public String moduleId() {
        return "web";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();
        HarborApi api = ctx.api();

        if (config.getBoolean("events.publish", true)) {
            EventRelay r =
                    new EventRelay(
                            this,
                            api,
                            new EventSerializer(id -> EventRelay.contentName(api, id)),
                            config.getStringList("events.exclude"),
                            getSLF4JLogger());
            int count = r.register();
            relay = r;
            getSLF4JLogger().info("외부 이벤트 {}종을 harbor:events로 발행합니다", count);
            r.emitServer(
                    "server.started",
                    Map.of("role", api.infra().role().toString().toLowerCase(Locale.ROOT)));
        }

        if (config.getBoolean("webhooks.enabled", false)) {
            List<WebhookTarget> targets = targets(config);
            WebhookDispatcher w =
                    new WebhookDispatcher(
                            api,
                            targets,
                            config.getInt("webhooks.retries", 3),
                            config.getInt("webhooks.queue_size", 1000),
                            Duration.ofSeconds(config.getInt("webhooks.timeout_seconds", 10)),
                            getSLF4JLogger());
            w.start();
            webhooks = w;
            getSLF4JLogger().info("웹훅 대상 {}개 (잠금을 잡은 서버만 보냅니다)", targets.size());
        }

        if (config.getBoolean("http.enabled", false)) {
            RecentEvents events = new RecentEvents(config.getInt("http.recent_events", 500));
            api.bus().subscribe(Channels.EVENTS, envelope -> events.add(envelope.payload()));
            recent = events;
            NetworkOnline network = new NetworkOnline();
            api.bus()
                    .subscribe(
                            ProxyMessages.HEARTBEATS,
                            envelope -> {
                                ProxyMessages.ServerHeartbeat beat = envelope.payload();
                                network.beat(
                                        beat.serverId(), beat.online(), System.currentTimeMillis());
                            });
            Calls calls = new Calls(api, ctx.tasks(), getSLF4JLogger());
            Router router = new Router();
            CoreRoutes.register(router, calls, events, network);
            if (CoreRoutes.enabled("Harbor-Economy")) {
                ModuleRoutes.economy(router, calls);
            }
            if (CoreRoutes.enabled("Harbor-Collection")) {
                ModuleRoutes.collection(router, calls);
            }
            if (CoreRoutes.enabled("Harbor-Season")) {
                ModuleRoutes.season(router, calls);
            }
            List<ApiServer.Token> tokens = new ArrayList<>();
            for (Map<?, ?> t : config.getMapList("http.tokens")) {
                Object name = t.get("name");
                Object token = t.get("token");
                if (name != null && token != null) {
                    tokens.add(new ApiServer.Token(String.valueOf(name), String.valueOf(token)));
                }
            }
            ApiServer server =
                    new ApiServer(
                            router,
                            tokens,
                            config.getInt("http.rate_limit_per_minute", 120),
                            config.getStringList("http.cors_origins"),
                            getSLF4JLogger());
            String bind = config.getString("http.bind", "127.0.0.1");
            int port = config.getInt("http.port", 8765);
            try {
                server.start(bind == null ? "127.0.0.1" : bind, port);
                http = server;
                getSLF4JLogger().info("REST API {}:{} (경로 {}개)", bind, port, router.list().size());
            } catch (java.io.IOException e) {
                getSLF4JLogger().error("REST API를 열지 못했습니다 ({}:{})", bind, port, e);
            }
        }

        ctx.adminCommand(adminCommand(api));
    }

    @Override
    protected void disable() {
        EventRelay r = relay;
        if (r != null) {
            r.emitServer("server.stopping", Map.of());
        }
        WebhookDispatcher w = webhooks;
        if (w != null) {
            w.stop();
        }
        ApiServer h = http;
        if (h != null) {
            h.stop();
        }
    }

    private List<WebhookTarget> targets(FileConfiguration config) {
        List<WebhookTarget> out = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("webhooks.targets")) {
            String name =
                    String.valueOf(
                            raw.containsKey("name") ? raw.get("name") : "webhook" + out.size());
            Object url = raw.get("url");
            if (url == null || String.valueOf(url).isBlank()) {
                getSLF4JLogger().warn("웹훅 {}에 url이 없어 건너뜁니다", name);
                continue;
            }
            WebhookTarget.Format format =
                    "json".equalsIgnoreCase(String.valueOf(raw.get("format")))
                            ? WebhookTarget.Format.JSON
                            : WebhookTarget.Format.DISCORD;
            List<String> events = new ArrayList<>();
            if (raw.get("events") instanceof List<?> list) {
                list.forEach(e -> events.add(String.valueOf(e)));
            }
            if (events.isEmpty()) {
                events.add("*");
            }
            Map<String, String> where = new java.util.LinkedHashMap<>();
            if (raw.get("where") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> where.put(String.valueOf(k), String.valueOf(v)));
            }
            Object secret = raw.get("secret");
            out.add(
                    new WebhookTarget(
                            name,
                            String.valueOf(url),
                            format,
                            secret == null ? "" : String.valueOf(secret),
                            events,
                            where));
        }
        return out;
    }

    // ------------------------------------------------------------------ admin commands

    private LiteralArgumentBuilder<CommandSourceStack> adminCommand(HarborApi api) {
        return Commands.literal("web")
                .requires(src -> src.getSender().hasPermission("harbor.admin.web"))
                .then(
                        Commands.literal("status")
                                .executes(
                                        ctx -> {
                                            var sender = ctx.getSource().getSender();
                                            EventRelay r = relay;
                                            WebhookDispatcher w = webhooks;
                                            api.messages()
                                                    .send(
                                                            sender,
                                                            "web.admin.status",
                                                            Placeholder.unparsed(
                                                                    "events",
                                                                    r == null
                                                                            ? "off"
                                                                            : String.valueOf(
                                                                                    r
                                                                                            .registered())),
                                                            Placeholder.unparsed(
                                                                    "webhooks",
                                                                    w == null
                                                                            ? "off"
                                                                            : (w.leader()
                                                                                            ? "leader"
                                                                                            : "standby")
                                                                                    + " "
                                                                                    + w.stats()),
                                                            Placeholder.unparsed(
                                                                    "http",
                                                                    http == null ? "off" : "on"));
                                            return Command.SINGLE_SUCCESS;
                                        }))
                .then(
                        Commands.literal("test")
                                .then(
                                        Commands.argument("target", StringArgumentType.word())
                                                .suggests(
                                                        (c, b) -> {
                                                            WebhookDispatcher w = webhooks;
                                                            if (w != null) {
                                                                w.targets()
                                                                        .forEach(
                                                                                t ->
                                                                                        b.suggest(
                                                                                                t
                                                                                                        .name()));
                                                            }
                                                            return b.buildFuture();
                                                        })
                                                .executes(
                                                        ctx -> {
                                                            var sender =
                                                                    ctx.getSource().getSender();
                                                            WebhookDispatcher w = webhooks;
                                                            String target =
                                                                    StringArgumentType.getString(
                                                                            ctx, "target");
                                                            ExternalEvent event =
                                                                    new ExternalEvent(
                                                                            UUID.randomUUID()
                                                                                    .toString(),
                                                                            "server.webhook_test",
                                                                            1,
                                                                            api.infra().serverId(),
                                                                            Instant.now()
                                                                                    .toString(),
                                                                            null,
                                                                            sender.getName(),
                                                                            Map.of(
                                                                                    "target",
                                                                                    target));
                                                            boolean ok =
                                                                    w != null
                                                                            && w.test(
                                                                                    target, event);
                                                            api.messages()
                                                                    .send(
                                                                            sender,
                                                                            ok
                                                                                    ? "web.admin.test_queued"
                                                                                    : "web.admin.test_failed",
                                                                            Placeholder.unparsed(
                                                                                    "target",
                                                                                    target));
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        Commands.literal("events")
                                .executes(
                                        ctx -> {
                                            var sender = ctx.getSource().getSender();
                                            RecentEvents events = recent;
                                            if (events == null) {
                                                api.messages().send(sender, "web.admin.no_recent");
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            long from = Math.max(0, events.last() - 10);
                                            for (RecentEvents.Item item :
                                                    events.after(from, "", 10)) {
                                                api.messages()
                                                        .send(
                                                                sender,
                                                                "web.admin.event_line",
                                                                Placeholder.unparsed(
                                                                        "seq",
                                                                        String.valueOf(item.seq())),
                                                                Placeholder.unparsed(
                                                                        "type",
                                                                        item.event().type()),
                                                                Placeholder.unparsed(
                                                                        "server",
                                                                        item.event().server()),
                                                                Placeholder.unparsed(
                                                                        "player",
                                                                        String.valueOf(
                                                                                item.event()
                                                                                        .playerName())));
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }));
    }
}
