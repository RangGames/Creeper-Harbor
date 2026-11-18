package wiki.creeper.harbor.boss.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.BossRecord;
import wiki.creeper.harbor.boss.api.BossRecordService;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.BossService;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.api.WorldBossService;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.PendingReward;
import wiki.creeper.harbor.boss.encounter.EncounterManager;
import wiki.creeper.harbor.boss.instance.InstanceManager;
import wiki.creeper.harbor.boss.world.WorldBossManager;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

/**
 * /boss (/보스) info · records · claim · enter · role, /harbor boss start · end · world · pending ·
 * debug · list (docs 20 10.5). 관리자 명령은 모두 admin_log에 남긴다.
 */
public final class BossCommands {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final HarborApi api;
    private final EncounterManager encounters;
    private final BossRecordService records;
    private final WorldBossManager world;
    private final InstanceManager instances;

    public BossCommands(
            HarborApi api,
            EncounterManager encounters,
            BossRecordService records,
            WorldBossManager world,
            InstanceManager instances) {
        this.api = api;
        this.encounters = encounters;
        this.records = records;
        this.world = world;
        this.instances = instances;
    }

    // ------------------------------------------------------------------ 플레이어 명령

    public void registerPlayerCommands(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("boss")
                        .requires(src -> src.getSender().hasPermission("harbor.boss"))
                        .then(Commands.literal("info").then(bossArg().executes(this::info)))
                        .then(
                                Commands.literal("records")
                                        .then(
                                                bossArg()
                                                        .executes(ctx -> records(ctx, false))
                                                        .then(
                                                                Commands.literal("all")
                                                                        .executes(
                                                                                ctx ->
                                                                                        records(
                                                                                                ctx,
                                                                                                true)))))
                        .then(
                                Commands.literal("claim")
                                        .requires(BossCommands::isPlayer)
                                        .executes(this::claim))
                        .then(
                                Commands.literal("enter")
                                        .requires(BossCommands::isPlayer)
                                        .then(bossArg().executes(this::enter)))
                        .then(
                                Commands.literal("role")
                                        .requires(BossCommands::isPlayer)
                                        .then(PartyCommands.roleArg().executes(this::role)));
        commands.register(root.build(), "보스전 정보·기록·보상", List.of("보스"));
    }

    private static boolean isPlayer(CommandSourceStack src) {
        return src.getExecutor() instanceof Player;
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> bossArg() {
        return Commands.argument("boss", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            for (BossDef d : encounters.definitions()) {
                                b.suggest(d.id().id().name());
                            }
                            return b.buildFuture();
                        });
    }

    /** 보스 이름(swamp_catfish) · boss/swamp_catfish · harbor:boss/swamp_catfish 모두 받는다. */
    private Optional<BossDef> boss(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "boss");
        try {
            HarborId id = HarborId.parse(raw.contains("/") ? raw : BossId.PREFIX + raw);
            return encounters.definition(new BossId(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<BossDef> def = boss(ctx);
        if (def.isEmpty()) {
            api.messages().send(sender, "boss.cmd.unknown_boss");
            return 0;
        }
        BossDef d = def.get();
        BossBalance bb = encounters.balance();
        Component name = MiniMessage.miniMessage().deserialize(d.name());
        int limit = bb.weeklyLimit(d.type());
        int timeLimit =
                d.timeLimitSeconds() > 0 ? d.timeLimitSeconds() : bb.timeLimitSeconds(d.type());
        api.messages()
                .send(
                        sender,
                        "boss.cmd.info",
                        Placeholder.component("boss", name),
                        Placeholder.component(
                                "type", api.messages().get("boss.type." + d.type().key())),
                        Placeholder.unparsed("min", String.valueOf(d.party().min())),
                        Placeholder.unparsed("max", String.valueOf(d.party().max())),
                        Placeholder.unparsed(
                                "time",
                                (timeLimit / 60) + ":" + String.format("%02d", timeLimit % 60)),
                        Placeholder.unparsed("limit", String.valueOf(limit)));
        if (sender instanceof Player p && !d.story()) {
            var _ =
                    SeasonServices.limits()
                            .remaining(
                                    p.getUniqueId(),
                                    WeeklyLimitService.bossKey(d.id().id().name()),
                                    limit)
                            .thenAccept(
                                    r ->
                                            api.messages()
                                                    .send(
                                                            p,
                                                            "boss.cmd.weekly",
                                                            Placeholder.unparsed(
                                                                    "left",
                                                                    String.valueOf(r.remaining())),
                                                            Placeholder.unparsed(
                                                                    "limit",
                                                                    String.valueOf(limit))));
        }
        int season = SeasonServices.clock().current().value();
        var _ =
                records.top(d.id(), season, 1)
                        .thenAccept(
                                list -> {
                                    if (list.isEmpty()) {
                                        api.messages().send(sender, "boss.cmd.no_record");
                                    } else {
                                        sendRecord(sender, 1, list.getFirst());
                                    }
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int records(CommandContext<CommandSourceStack> ctx, boolean all) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<BossDef> def = boss(ctx);
        if (def.isEmpty()) {
            api.messages().send(sender, "boss.cmd.unknown_boss");
            return 0;
        }
        int season = SeasonServices.clock().current().value();
        var future =
                all ? records.allTime(def.get().id(), 10) : records.top(def.get().id(), season, 10);
        var _ =
                future.thenAccept(
                        list -> {
                            api.messages()
                                    .send(
                                            sender,
                                            all ? "boss.cmd.records_all" : "boss.cmd.records",
                                            Placeholder.component(
                                                    "boss",
                                                    MiniMessage.miniMessage()
                                                            .deserialize(def.get().name())),
                                            Placeholder.unparsed("season", String.valueOf(season)));
                            if (list.isEmpty()) {
                                api.messages().send(sender, "boss.cmd.no_record");
                            }
                            for (int i = 0; i < list.size(); i++) {
                                sendRecord(sender, i + 1, list.get(i));
                            }
                        });
        return Command.SINGLE_SUCCESS;
    }

    private void sendRecord(CommandSender sender, int rank, BossRecord r) {
        List<UUID> members = r.members();
        String leader = members.isEmpty() ? "?" : name(members.getFirst());
        api.messages()
                .send(
                        sender,
                        "boss.cmd.record_line",
                        Placeholder.unparsed("rank", String.valueOf(rank)),
                        Placeholder.unparsed("time", formatMs(r.durationMs())),
                        Placeholder.unparsed("leader", leader),
                        Placeholder.unparsed("count", String.valueOf(members.size())),
                        Placeholder.unparsed("season", String.valueOf(r.season())),
                        Placeholder.unparsed(
                                "date", DATE.format(r.clearedAt().atZone(api.time().zone()))));
    }

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    private static String name(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        String n = op.getName();
        return n == null ? u.toString().substring(0, 8) : n;
    }

    private int claim(CommandContext<CommandSourceStack> ctx) {
        encounters
                .rewards()
                .deliverPending(
                        java.util.Objects.requireNonNull((Player) ctx.getSource().getExecutor()),
                        true);
        return Command.SINGLE_SUCCESS;
    }

    private int enter(CommandContext<CommandSourceStack> ctx) {
        Player p = java.util.Objects.requireNonNull((Player) ctx.getSource().getExecutor());
        Optional<BossDef> def = boss(ctx);
        if (def.isEmpty()) {
            api.messages().send(p, "boss.cmd.unknown_boss");
            return 0;
        }
        InstanceManager.EnterResult r = instances.requestEntry(p, def.get().id());
        if (r != InstanceManager.EnterResult.REQUESTED) {
            api.messages().send(p, "boss.instance." + r.name().toLowerCase(Locale.ROOT));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 준비 단계면 이번 조우 역할, 아니면 선호 역할 (BossProfile). */
    private int role(CommandContext<CommandSourceStack> ctx) {
        Player p = java.util.Objects.requireNonNull((Player) ctx.getSource().getExecutor());
        Optional<BossRole> role = PartyCommands.role(ctx);
        if (role.isEmpty()) {
            api.messages().send(p, "boss.role.unknown");
            return 0;
        }
        if (!encounters.chooseRole(p.getUniqueId(), role.get())) {
            var hp = api.players().get(p);
            hp.set(
                    EncounterManager.PROFILE,
                    hp.get(EncounterManager.PROFILE).withPreferred(role.get()));
        }
        api.messages()
                .send(
                        p,
                        "boss.role.preferred",
                        Placeholder.component(
                                "role", api.messages().get("boss.role." + role.get().key())));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ 관리자 명령

    /** /harbor boss … (harbor.admin.boss는 Core가 건다. 하위 권한은 여기서). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("boss")
                .then(
                        perm(Commands.literal("start"), "start")
                                .then(
                                        bossArg()
                                                .executes(ctx -> start(ctx, false))
                                                .then(
                                                        Commands.argument(
                                                                        "target",
                                                                        ArgumentTypes.player())
                                                                .executes(
                                                                        ctx -> start(ctx, true)))))
                .then(
                        perm(Commands.literal("end"), "end")
                                .then(
                                        encounterArg()
                                                .then(
                                                        Commands.argument(
                                                                        "result",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        (c, b) -> {
                                                                            b.suggest("clear");
                                                                            b.suggest("fail");
                                                                            b.suggest("abort");
                                                                            return b.buildFuture();
                                                                        })
                                                                .executes(this::end))))
                .then(
                        perm(Commands.literal("world"), "world")
                                .then(
                                        Commands.literal("start")
                                                .then(bossArg().executes(this::worldStart)))
                                .then(Commands.literal("resume").executes(this::worldResume))
                                .then(Commands.literal("stop").executes(this::worldStop))
                                .then(Commands.literal("status").executes(this::worldStatus)))
                .then(
                        perm(Commands.literal("pending"), "pending")
                                .then(
                                        Commands.argument("target", ArgumentTypes.player())
                                                .executes(ctx -> pending(ctx, false))
                                                .then(
                                                        Commands.literal("redeliver")
                                                                .executes(
                                                                        ctx ->
                                                                                pending(
                                                                                        ctx,
                                                                                        true)))))
                .then(
                        perm(Commands.literal("debug"), "debug")
                                .then(encounterArg().executes(this::debug)))
                .then(perm(Commands.literal("list"), "debug").executes(this::list));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T perm(
            T builder, String sub) {
        return builder.requires(src -> src.getSender().hasPermission("harbor.admin.boss." + sub));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> encounterArg() {
        return Commands.argument("encounter", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            for (EncounterInfo e : encounters.encounters()) {
                                b.suggest(e.id().toString());
                            }
                            return b.buildFuture();
                        });
    }

    private Optional<UUID> encounterId(CommandContext<CommandSourceStack> ctx) {
        try {
            return Optional.of(UUID.fromString(StringArgumentType.getString(ctx, "encounter")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private int start(CommandContext<CommandSourceStack> ctx, boolean other)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Optional<BossDef> def = boss(ctx);
        if (def.isEmpty()) {
            api.messages().send(sender, "boss.cmd.unknown_boss");
            return 0;
        }
        Player leader;
        if (other) {
            leader =
                    ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                            .resolve(ctx.getSource())
                            .getFirst();
        } else if (ctx.getSource().getExecutor() instanceof Player p) {
            leader = p;
        } else {
            api.messages().send(sender, "boss.cmd.need_player");
            return 0;
        }
        BossService.StartResult r = encounters.forceStart(def.get().id(), leader);
        api.messages()
                .send(sender, "boss.admin.start_result", Placeholder.unparsed("result", r.name()));
        log(sender, "start", leader.getUniqueId().toString(), def.get().id() + " " + r);
        return r == BossService.StartResult.STARTED ? Command.SINGLE_SUCCESS : 0;
    }

    private int end(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> id = encounterId(ctx);
        EncounterState result =
                switch (StringArgumentType.getString(ctx, "result")) {
                    case "clear" -> EncounterState.CLEARED;
                    case "fail" -> EncounterState.FAILED;
                    default -> EncounterState.ABORTED;
                };
        if (id.isEmpty() || !encounters.forceEnd(id.get(), result)) {
            api.messages().send(sender, "boss.admin.no_encounter");
            return 0;
        }
        api.messages().send(sender, "boss.admin.done");
        log(sender, "end", id.get().toString(), result.name());
        return Command.SINGLE_SUCCESS;
    }

    private int worldStart(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<BossDef> def = boss(ctx);
        if (def.isEmpty()) {
            api.messages().send(sender, "boss.cmd.unknown_boss");
            return 0;
        }
        var _ = world.start(def.get().id()).thenAccept(r -> worldResult(sender, r));
        log(sender, "world.start", null, def.get().id().toString());
        return Command.SINGLE_SUCCESS;
    }

    private int worldResume(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ = world.resume().thenAccept(r -> worldResult(sender, r));
        log(sender, "world.resume", null, null);
        return Command.SINGLE_SUCCESS;
    }

    private void worldResult(CommandSender sender, WorldBossService.StartResult r) {
        api.messages()
                .send(sender, "boss.admin.world_result", Placeholder.unparsed("result", r.name()));
    }

    private int worldStop(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        boolean ok = world.stop();
        api.messages().send(sender, ok ? "boss.admin.done" : "boss.admin.no_encounter");
        log(sender, "world.stop", null, null);
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private int worldStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<wiki.creeper.harbor.boss.api.WorldBossStatus> s = world.status();
        if (s.isEmpty()) {
            api.messages().send(sender, "boss.admin.no_encounter");
            return 0;
        }
        api.messages()
                .send(
                        sender,
                        "boss.admin.world_status",
                        Placeholder.unparsed("boss", s.get().boss().toString()),
                        Placeholder.unparsed("stage", String.valueOf(s.get().stage())),
                        Placeholder.unparsed("percent", String.valueOf(s.get().percent())),
                        Placeholder.unparsed(
                                "participants", String.valueOf(s.get().participants())),
                        Placeholder.unparsed("server", s.get().server()),
                        Placeholder.unparsed("state", s.get().state().name()));
        return Command.SINGLE_SUCCESS;
    }

    private int pending(CommandContext<CommandSourceStack> ctx, boolean redeliver)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        if (redeliver) {
            encounters.rewards().deliverPending(target, true);
            api.messages().send(sender, "boss.admin.done");
            log(sender, "pending.redeliver", target.getUniqueId().toString(), null);
            return Command.SINGLE_SUCCESS;
        }
        var _ =
                encounters
                        .rewards()
                        .pending(target.getUniqueId())
                        .thenAccept(
                                list -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "boss.admin.pending_header",
                                                    Placeholder.unparsed(
                                                            "player", target.getName()),
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(list.size())));
                                    for (PendingReward r : list) {
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "boss.admin.pending_line",
                                                        Placeholder.unparsed(
                                                                "id", r.id().toString()),
                                                        Placeholder.unparsed("kind", r.kind()),
                                                        Placeholder.unparsed(
                                                                "items", r.entries().toString()));
                                    }
                                });
        log(sender, "pending", target.getUniqueId().toString(), null);
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> id = encounterId(ctx);
        List<String> lines = id.map(encounters::debug).orElse(List.of());
        if (lines.isEmpty()) {
            api.messages().send(sender, "boss.admin.no_encounter");
            return 0;
        }
        for (String line : lines) {
            api.messages()
                    .send(sender, "boss.admin.debug_line", Placeholder.unparsed("line", line));
        }
        log(sender, "debug", id.get().toString(), null);
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<EncounterInfo> all = encounters.encounters();
        api.messages()
                .send(
                        sender,
                        "boss.admin.list_header",
                        Placeholder.unparsed("count", String.valueOf(all.size())),
                        Placeholder.unparsed(
                                "reservations", String.valueOf(instances.reservations())));
        for (EncounterInfo e : all) {
            api.messages()
                    .send(
                            sender,
                            "boss.admin.list_line",
                            Placeholder.unparsed("id", e.id().toString()),
                            Placeholder.unparsed("boss", e.boss().toString()),
                            Placeholder.unparsed("state", e.state().name()),
                            Placeholder.unparsed("members", String.valueOf(e.members().size())),
                            Placeholder.unparsed(
                                    "hp", String.valueOf(Math.round(e.hpRatio() * 100))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void log(
            CommandSender sender,
            String action,
            @org.jspecify.annotations.Nullable String target,
            @org.jspecify.annotations.Nullable String detail) {
        String admin = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (var ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, admin);
                                        ps.setString(2, "boss." + action);
                                        ps.setString(3, target);
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                });
    }
}
