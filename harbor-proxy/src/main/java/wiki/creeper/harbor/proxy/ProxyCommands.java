package wiki.creeper.harbor.proxy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * 프록시 명령 (docs 20 1.5). Velocity 기본 /server는 등록 해제한다 (모든 이동은 MoveCoordinator를 거쳐야 함). 모든 /hp 명령은
 * admin_log에 남긴다.
 */
final class ProxyCommands {

    /** admin_log 기록 함수. */
    @FunctionalInterface
    interface AdminLogger {
        void record(CommandSource admin, String action, String target, String detail);
    }

    private final Object plugin;
    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ProxyText text;
    private final MoveCoordinator mover;
    private final QueueManager queue;
    private final MaintenanceService maintenance;
    private final ServerDirectory directory;
    private final ChatRelay chat;
    private final MessageBus bus;
    private final AdminLogger adminLog;

    ProxyCommands(
            Object plugin,
            ProxyServer proxy,
            ProxyConfig config,
            ProxyText text,
            MoveCoordinator mover,
            QueueManager queue,
            MaintenanceService maintenance,
            ServerDirectory directory,
            ChatRelay chat,
            MessageBus bus,
            AdminLogger adminLog) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.text = text;
        this.mover = mover;
        this.queue = queue;
        this.maintenance = maintenance;
        this.directory = directory;
        this.chat = chat;
        this.bus = bus;
        this.adminLog = adminLog;
    }

    void register() {
        CommandManager manager = proxy.getCommandManager();
        manager.unregister("server");
        register(manager, lobby(), "lobby", "hub");
        register(manager, queueStatus(), "queue");
        register(manager, whisper(), "w", "whisper");
        register(manager, reply(), "r", "reply");
        register(manager, admin());
    }

    private void register(
            CommandManager manager, LiteralArgumentBuilder<CommandSource> root, String... aliases) {
        BrigadierCommand command = new BrigadierCommand(root);
        manager.register(
                manager.metaBuilder(command).aliases(aliases).plugin(plugin).build(), command);
    }

    // ------------------------------------------------------------------ 플레이어 명령

    private LiteralArgumentBuilder<CommandSource> lobby() {
        return BrigadierCommand.literalArgumentBuilder("로비")
                .requires(src -> src instanceof Player)
                .executes(
                        ctx -> {
                            Player player = (Player) ctx.getSource();
                            boolean onMain =
                                    player.getCurrentServer()
                                            .map(
                                                    s ->
                                                            config.mainServers()
                                                                    .containsKey(
                                                                            s.getServerInfo()
                                                                                    .getName()))
                                            .orElse(false);
                            var _ =
                                    mover.move(player, config.lobby(), "lobby")
                                            .thenAccept(
                                                    result -> {
                                                        if (result.ok() && onMain) {
                                                            queue.grantGrace(player.getUniqueId());
                                                        }
                                                        notifyFailure(player, result);
                                                    });
                            return Command.SINGLE_SUCCESS;
                        });
    }

    private LiteralArgumentBuilder<CommandSource> queueStatus() {
        return BrigadierCommand.literalArgumentBuilder("대기열")
                .requires(src -> src instanceof Player)
                .executes(
                        ctx -> {
                            Player player = (Player) ctx.getSource();
                            OptionalInt position = queue.position(player.getUniqueId());
                            if (position.isEmpty()) {
                                player.sendMessage(text.get("queue.not_queued"));
                            } else {
                                player.sendMessage(
                                        text.get(
                                                "queue.position",
                                                Placeholder.unparsed(
                                                        "position",
                                                        String.valueOf(position.getAsInt())),
                                                Placeholder.unparsed(
                                                        "eta", queue.eta(position.getAsInt()))));
                            }
                            return Command.SINGLE_SUCCESS;
                        });
    }

    private LiteralArgumentBuilder<CommandSource> whisper() {
        return BrigadierCommand.literalArgumentBuilder("귓")
                .requires(src -> src instanceof Player)
                .then(
                        BrigadierCommand.requiredArgumentBuilder(
                                        "target", StringArgumentType.word())
                                .suggests(
                                        (ctx, builder) -> {
                                            proxy.getAllPlayers()
                                                    .forEach(p -> builder.suggest(p.getUsername()));
                                            return builder.buildFuture();
                                        })
                                .then(
                                        BrigadierCommand.requiredArgumentBuilder(
                                                        "message",
                                                        StringArgumentType.greedyString())
                                                .executes(
                                                        ctx -> {
                                                            Player from = (Player) ctx.getSource();
                                                            Optional<Player> to =
                                                                    proxy.getPlayer(
                                                                            StringArgumentType
                                                                                    .getString(
                                                                                            ctx,
                                                                                            "target"));
                                                            if (to.isEmpty()) {
                                                                from.sendMessage(
                                                                        text.get("chat.no_target"));
                                                                return 0;
                                                            }
                                                            chat.whisper(
                                                                    from.getUniqueId(),
                                                                    from.getUsername(),
                                                                    to.get().getUniqueId(),
                                                                    StringArgumentType.getString(
                                                                            ctx, "message"));
                                                            return Command.SINGLE_SUCCESS;
                                                        })));
    }

    private LiteralArgumentBuilder<CommandSource> reply() {
        return BrigadierCommand.literalArgumentBuilder("답장")
                .requires(src -> src instanceof Player)
                .then(
                        BrigadierCommand.requiredArgumentBuilder(
                                        "message", StringArgumentType.greedyString())
                                .executes(
                                        ctx -> {
                                            Player from = (Player) ctx.getSource();
                                            Optional<UUID> partner =
                                                    chat.lastPartner(from.getUniqueId());
                                            if (partner.isEmpty()) {
                                                from.sendMessage(text.get("chat.no_target"));
                                                return 0;
                                            }
                                            chat.whisper(
                                                    from.getUniqueId(),
                                                    from.getUsername(),
                                                    partner.get(),
                                                    StringArgumentType.getString(ctx, "message"));
                                            return Command.SINGLE_SUCCESS;
                                        }));
    }

    // ------------------------------------------------------------------ /hp 관리 명령

    private LiteralArgumentBuilder<CommandSource> admin() {
        return BrigadierCommand.literalArgumentBuilder("hp")
                .requires(src -> src.hasPermission("harbor.proxy"))
                .then(
                        BrigadierCommand.literalArgumentBuilder("maintenance")
                                .requires(src -> src.hasPermission("harbor.proxy.maintenance"))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("on")
                                                .executes(ctx -> maintenanceOn(ctx, 10, "점검"))
                                                .then(
                                                        BrigadierCommand.requiredArgumentBuilder(
                                                                        "minutes",
                                                                        IntegerArgumentType.integer(
                                                                                0, 1440))
                                                                .executes(
                                                                        ctx ->
                                                                                maintenanceOn(
                                                                                        ctx,
                                                                                        IntegerArgumentType
                                                                                                .getInteger(
                                                                                                        ctx,
                                                                                                        "minutes"),
                                                                                        "점검"))
                                                                .then(
                                                                        BrigadierCommand
                                                                                .requiredArgumentBuilder(
                                                                                        "reason",
                                                                                        StringArgumentType
                                                                                                .greedyString())
                                                                                .executes(
                                                                                        ctx ->
                                                                                                maintenanceOn(
                                                                                                        ctx,
                                                                                                        IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        ctx,
                                                                                                                        "minutes"),
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        ctx,
                                                                                                                        "reason"))))))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("off")
                                                .executes(
                                                        ctx -> {
                                                            maintenance.turnOff();
                                                            adminLog.record(
                                                                    ctx.getSource(),
                                                                    "maintenance.off",
                                                                    "",
                                                                    "");
                                                            ctx.getSource()
                                                                    .sendMessage(
                                                                            text.get("admin.done"));
                                                            return Command.SINGLE_SUCCESS;
                                                        }))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("status")
                                                .executes(
                                                        ctx -> {
                                                            MaintenanceService.State s =
                                                                    maintenance.state();
                                                            ctx.getSource()
                                                                    .sendMessage(
                                                                            text.get(
                                                                                    "maintenance.status",
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "state",
                                                                                                    s
                                                                                                                    .on()
                                                                                                            ? "ON"
                                                                                                            : "OFF"),
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "reason",
                                                                                                    s
                                                                                                            .reason()),
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "until",
                                                                                                    s
                                                                                                                            .until()
                                                                                                                    == 0
                                                                                                            ? "-"
                                                                                                            : Instant
                                                                                                                    .ofEpochMilli(
                                                                                                                            s
                                                                                                                                    .until())
                                                                                                                    .toString())));
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        BrigadierCommand.literalArgumentBuilder("send")
                                .requires(src -> src.hasPermission("harbor.proxy.send"))
                                .then(
                                        BrigadierCommand.requiredArgumentBuilder(
                                                        "target", StringArgumentType.word())
                                                .then(
                                                        BrigadierCommand.requiredArgumentBuilder(
                                                                        "server",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        (ctx, builder) -> {
                                                                            proxy.getAllServers()
                                                                                    .forEach(
                                                                                            s ->
                                                                                                    builder
                                                                                                            .suggest(
                                                                                                                    s.getServerInfo()
                                                                                                                            .getName()));
                                                                            return builder
                                                                                    .buildFuture();
                                                                        })
                                                                .executes(this::send))))
                .then(
                        BrigadierCommand.literalArgumentBuilder("queue")
                                .requires(src -> src.hasPermission("harbor.proxy.queue"))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("list")
                                                .executes(
                                                        ctx -> {
                                                            List<UUID> all = queue.snapshot();
                                                            ctx.getSource()
                                                                    .sendMessage(
                                                                            text.get(
                                                                                    "queue.list",
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "count",
                                                                                                    String
                                                                                                            .valueOf(
                                                                                                                    all
                                                                                                                            .size()))));
                                                            return Command.SINGLE_SUCCESS;
                                                        }))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("pause")
                                                .executes(ctx -> queuePause(ctx, true)))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("resume")
                                                .executes(ctx -> queuePause(ctx, false)))
                                .then(
                                        BrigadierCommand.literalArgumentBuilder("cap")
                                                .then(
                                                        BrigadierCommand.requiredArgumentBuilder(
                                                                        "cap",
                                                                        IntegerArgumentType.integer(
                                                                                0, 1000))
                                                                .executes(
                                                                        ctx -> {
                                                                            int cap =
                                                                                    IntegerArgumentType
                                                                                            .getInteger(
                                                                                                    ctx,
                                                                                                    "cap");
                                                                            queue.setCap(cap);
                                                                            adminLog.record(
                                                                                    ctx.getSource(),
                                                                                    "queue.cap",
                                                                                    "",
                                                                                    String.valueOf(
                                                                                            cap));
                                                                            ctx.getSource()
                                                                                    .sendMessage(
                                                                                            text
                                                                                                    .get(
                                                                                                            "admin.done"));
                                                                            return Command
                                                                                    .SINGLE_SUCCESS;
                                                                        }))))
                .then(
                        BrigadierCommand.literalArgumentBuilder("servers")
                                .requires(src -> src.hasPermission("harbor.proxy.servers"))
                                .executes(this::servers))
                .then(
                        BrigadierCommand.literalArgumentBuilder("announce")
                                .requires(src -> src.hasPermission("harbor.proxy.announce"))
                                .then(
                                        BrigadierCommand.requiredArgumentBuilder(
                                                        "message",
                                                        StringArgumentType.greedyString())
                                                .executes(
                                                        ctx -> {
                                                            String message =
                                                                    StringArgumentType.getString(
                                                                            ctx, "message");
                                                            adminLog.record(
                                                                    ctx.getSource(),
                                                                    "announce",
                                                                    "",
                                                                    message);
                                                            bus.publish(
                                                                    Channels.ANNOUNCEMENTS,
                                                                    new Channels.Announcement(
                                                                            "<gold>[공지]</gold> "
                                                                                    + MiniMessage
                                                                                            .miniMessage()
                                                                                            .escapeTags(
                                                                                                    message)));
                                                            return Command.SINGLE_SUCCESS;
                                                        })));
    }

    private int maintenanceOn(CommandContext<CommandSource> ctx, int minutes, String reason) {
        maintenance.turnOn(minutes, reason);
        adminLog.record(ctx.getSource(), "maintenance.on", "", minutes + "분 " + reason);
        ctx.getSource().sendMessage(text.get("admin.done"));
        return Command.SINGLE_SUCCESS;
    }

    private int queuePause(CommandContext<CommandSource> ctx, boolean pause) {
        queue.setPaused(pause);
        adminLog.record(ctx.getSource(), pause ? "queue.pause" : "queue.resume", "", "");
        if (pause) {
            proxy.getAllPlayers()
                    .forEach(
                            p -> {
                                if (queue.position(p.getUniqueId()).isPresent()) {
                                    p.sendMessage(text.get("queue.paused"));
                                }
                            });
        }
        ctx.getSource().sendMessage(text.get("admin.done"));
        return Command.SINGLE_SUCCESS;
    }

    private int send(CommandContext<CommandSource> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        String server = StringArgumentType.getString(ctx, "server");
        adminLog.record(ctx.getSource(), "send", target, server);
        List<Player> players =
                target.equals("all")
                        ? List.copyOf(proxy.getAllPlayers())
                        : proxy.getPlayer(target).map(List::of).orElse(List.of());
        if (players.isEmpty()) {
            ctx.getSource().sendMessage(text.get("chat.no_target"));
            return 0;
        }
        for (Player player : players) {
            var _ =
                    mover.move(player, server, "admin_send")
                            .thenAccept(
                                    result -> {
                                        if (!result.ok()) {
                                            ctx.getSource()
                                                    .sendMessage(
                                                            text.get(
                                                                    "move.failed",
                                                                    Placeholder.unparsed(
                                                                            "reason",
                                                                            player.getUsername()
                                                                                    + ": "
                                                                                    + result
                                                                                            .reason())));
                                        }
                                    });
        }
        return Command.SINGLE_SUCCESS;
    }

    private int servers(CommandContext<CommandSource> ctx) {
        Instant now = Instant.now();
        for (Map.Entry<String, ServerDirectory.Entry> e : directory.all().entrySet()) {
            var beat = e.getValue().beat();
            ctx.getSource()
                    .sendMessage(
                            text.get(
                                    "admin.servers_line",
                                    Placeholder.unparsed("server", e.getKey()),
                                    Placeholder.unparsed("role", beat.role()),
                                    Placeholder.unparsed("online", String.valueOf(beat.online())),
                                    Placeholder.unparsed(
                                            "capacity", String.valueOf(beat.capacity())),
                                    Placeholder.unparsed(
                                            "tps", String.format(Locale.ROOT, "%.1f", beat.tps())),
                                    Placeholder.unparsed(
                                            "age",
                                            String.valueOf(
                                                    Duration.between(e.getValue().receivedAt(), now)
                                                            .toSeconds())),
                                    Placeholder.unparsed(
                                            "state",
                                            e.getValue().fresh(now) && beat.accepting()
                                                    ? "OK"
                                                    : "X")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void notifyFailure(Player player, MoveResult result) {
        if (result.ok()) {
            return;
        }
        player.sendMessage(
                result.reason().equals("busy")
                        ? text.get("move.busy")
                        : text.get("move.failed", Placeholder.unparsed("reason", result.reason())));
    }
}
