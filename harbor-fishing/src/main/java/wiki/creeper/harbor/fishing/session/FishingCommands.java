package wiki.creeper.harbor.fishing.session;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.fishing.api.TrapInfo;
import wiki.creeper.harbor.fishing.content.SeaEventPresets;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.food.ActiveFood;
import wiki.creeper.harbor.fishing.food.FoodManager;
import wiki.creeper.harbor.fishing.roll.RollEngine;

/** /harbor fishing … (docs 20 3.5). 권한 harbor.admin.fishing은 Core가 건다. */
public final class FishingCommands {

    private final HarborApi api;
    private final FishingManager fishing;
    private final FoodManager food;

    public FishingCommands(HarborApi api, FishingManager fishing, FoodManager food) {
        this.api = api;
        this.fishing = fishing;
        this.food = food;
    }

    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return baseAdmin()
                .then(statsCommand())
                .then(trapCommand())
                .then(eventCommand())
                .then(foodCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> baseAdmin() {
        return Commands.literal("fishing")
                .then(
                        Commands.literal("roll")
                                .then(
                                        target().executes(ctx -> roll(ctx, 1000))
                                                .then(
                                                        Commands.argument(
                                                                        "count",
                                                                        IntegerArgumentType.integer(
                                                                                1, 100_000))
                                                                .executes(
                                                                        ctx ->
                                                                                roll(
                                                                                        ctx,
                                                                                        IntegerArgumentType
                                                                                                .getInteger(
                                                                                                        ctx,
                                                                                                        "count"))))))
                .then(
                        Commands.literal("simulate")
                                .then(
                                        Commands.argument("region", ArgumentTypes.key())
                                                .suggests(
                                                        (ctx, b) -> {
                                                            api.content()
                                                                    .snapshot()
                                                                    .ids(RegionDef.TYPE.name())
                                                                    .forEach(
                                                                            id ->
                                                                                    b.suggest(
                                                                                            id
                                                                                                    .toString()));
                                                            return b.buildFuture();
                                                        })
                                                .then(
                                                        Commands.argument(
                                                                        "count",
                                                                        IntegerArgumentType.integer(
                                                                                1, 1_000_000))
                                                                .executes(this::simulate))))
                .then(
                        Commands.literal("pity")
                                .then(
                                        target().then(
                                                        fishArgument()
                                                                .executes(ctx -> pity(ctx, -1))
                                                                .then(
                                                                        Commands.literal("set")
                                                                                .then(
                                                                                        Commands
                                                                                                .argument(
                                                                                                        "value",
                                                                                                        IntegerArgumentType
                                                                                                                .integer(
                                                                                                                        0))
                                                                                                .executes(
                                                                                                        ctx ->
                                                                                                                pity(
                                                                                                                        ctx,
                                                                                                                        IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        ctx,
                                                                                                                                        "value"))))))))
                .then(Commands.literal("session").then(target().executes(this::session)))
                .then(
                        Commands.literal("force")
                                .then(
                                        target().then(
                                                        fishArgument()
                                                                .executes(ctx -> force(ctx, null))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "quality",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        Quality
                                                                                                                .MIN,
                                                                                                        Quality
                                                                                                                .MAX))
                                                                                .executes(
                                                                                        ctx ->
                                                                                                force(
                                                                                                        ctx,
                                                                                                        Quality
                                                                                                                .of(
                                                                                                                        IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        ctx,
                                                                                                                                        "quality"))))))))
                .then(
                        Commands.literal("macro")
                                .then(
                                        target().executes(ctx -> macro(ctx, false))
                                                .then(
                                                        Commands.literal("reset")
                                                                .executes(
                                                                        ctx -> macro(ctx, true)))));
    }

    private int roll(CommandContext<CommandSourceStack> ctx, int count)
            throws CommandSyntaxException {
        Player p = player(ctx);
        CommandSender sender = ctx.getSource().getSender();
        Optional<RegionKeyResolver.RegionKey> region = fishing.regionKey(FishingManager.loc(p));
        if (region.isEmpty()) {
            api.messages().send(sender, "fishing.no_region");
            return 0;
        }
        RollEngine engine = fishing.engine();
        Map<Grade, Integer> grades = new EnumMap<>(Grade.class);
        FishingData data = api.players().get(p).get(FishingManager.COMPONENT);
        RandomGenerator random = RandomGenerator.getDefault();
        for (int i = 0; i < count; i++) {
            engine.roll(
                            fishing.context(p, region.get(), null),
                            fishing.modifiers(p.getUniqueId(), Map.of(), region.get().region()),
                            data.pity(),
                            random)
                    .ifPresent(o -> grades.merge(o.fish().grade(), 1, Integer::sum));
        }
        api.messages()
                .send(
                        sender,
                        "fishing.admin.distribution",
                        Placeholder.unparsed("count", String.valueOf(count)),
                        Placeholder.unparsed("grades", percent(grades, count)));
        return Command.SINGLE_SUCCESS;
    }

    private int simulate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Key key = ctx.getArgument("region", Key.class);
        RegionId region;
        try {
            region = new RegionId(harborId(key));
        } catch (IllegalArgumentException e) {
            api.messages().send(sender, "fishing.no_region");
            return 0;
        }
        int count = IntegerArgumentType.getInteger(ctx, "count");
        var _ =
                fishing.simulate(region, count)
                        .thenAcceptAsync(
                                result ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "fishing.admin.simulation",
                                                        Placeholder.unparsed(
                                                                "count",
                                                                String.valueOf(result.casts())),
                                                        Placeholder.unparsed(
                                                                "grades",
                                                                percent(
                                                                        result.grades(),
                                                                        result.casts())),
                                                        Placeholder.unparsed(
                                                                "legend",
                                                                String.format(
                                                                        Locale.ROOT,
                                                                        "%.0f",
                                                                        result
                                                                                .legendAverageCasts()))),
                                api.database().executor());
        return Command.SINGLE_SUCCESS;
    }

    private int pity(CommandContext<CommandSourceStack> ctx, int value)
            throws CommandSyntaxException {
        Player p = player(ctx);
        FishDef fish = fish(ctx).orElse(null);
        if (fish == null) {
            api.messages().send(ctx.getSource().getSender(), "fishing.admin.unknown_fish");
            return 0;
        }
        HarborPlayer hp = api.players().get(p);
        if (value >= 0) {
            hp.set(
                    FishingManager.COMPONENT,
                    hp.get(FishingManager.COMPONENT).withPity(fish.id(), value));
        }
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "fishing.admin.pity",
                        Placeholder.unparsed("player", p.getName()),
                        Placeholder.unparsed("fish", fish.id().toString()),
                        Placeholder.unparsed(
                                "value",
                                String.valueOf(hp.get(FishingManager.COMPONENT).pity(fish.id()))));
        return Command.SINGLE_SUCCESS;
    }

    private int session(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        FishingSession s = fishing.sessionOf(p);
        Minigame game = s.minigame;
        FishDef fish = s.fish;
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "fishing.admin.session",
                        Placeholder.unparsed("player", p.getName()),
                        Placeholder.unparsed("state", s.state.name()),
                        Placeholder.unparsed("fish", fish == null ? "-" : fish.id().toString()),
                        Placeholder.unparsed(
                                "tension",
                                game == null
                                        ? "-"
                                        : String.format(Locale.ROOT, "%.2f", game.tension())),
                        Placeholder.unparsed(
                                "hp",
                                game == null
                                        ? "-"
                                        : String.format(
                                                Locale.ROOT, "%.0f%%", game.hpRatio() * 100)),
                        Placeholder.unparsed(
                                "pattern", game == null ? "-" : String.valueOf(game.active())));
        return Command.SINGLE_SUCCESS;
    }

    private int force(CommandContext<CommandSourceStack> ctx, @Nullable Quality quality)
            throws CommandSyntaxException {
        Player p = player(ctx);
        Optional<FishDef> fish = fish(ctx);
        if (fish.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "fishing.admin.unknown_fish");
            return 0;
        }
        FishingSession s = fishing.sessionOf(p);
        s.forcedFish = fish.get();
        s.forcedQuality = quality;
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "fishing.admin.forced",
                        Placeholder.unparsed("player", p.getName()),
                        Placeholder.unparsed("fish", fish.get().id().toString()));
        return Command.SINGLE_SUCCESS;
    }

    private int macro(CommandContext<CommandSourceStack> ctx, boolean reset)
            throws CommandSyntaxException {
        Player p = player(ctx);
        if (reset) {
            fishing.macro().reset(p.getUniqueId());
        }
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "fishing.admin.macro",
                        Placeholder.unparsed("player", p.getName()),
                        Placeholder.unparsed(
                                "score", String.valueOf(fishing.macro().score(p.getUniqueId()))),
                        Placeholder.unparsed(
                                "reasons",
                                String.join(", ", fishing.macro().reasons(p.getUniqueId()))));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ stats · trap · event ·
    // food

    private LiteralArgumentBuilder<CommandSourceStack> statsCommand() {
        return Commands.literal("stats")
                .executes(
                        ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Map<Grade, MinigameStats.Summary> summary =
                                    fishing.stats().summary(Instant.now());
                            if (summary.isEmpty()) {
                                api.messages().send(sender, "fishing.admin.stats_empty");
                            }
                            summary.forEach(
                                    (grade, sum) ->
                                            api.messages()
                                                    .send(
                                                            sender,
                                                            "fishing.admin.stats",
                                                            Placeholder.unparsed(
                                                                    "grade", grade.key()),
                                                            Placeholder.unparsed(
                                                                    "attempts",
                                                                    String.valueOf(sum.attempts())),
                                                            Placeholder.unparsed(
                                                                    "rate",
                                                                    String.format(
                                                                            Locale.ROOT,
                                                                            "%.1f%%",
                                                                            sum.successRate()
                                                                                    * 100)),
                                                            Placeholder.unparsed(
                                                                    "seconds",
                                                                    String.format(
                                                                            Locale.ROOT,
                                                                            "%.1f",
                                                                            sum
                                                                                    .averageSeconds()))));
                            return Command.SINGLE_SUCCESS;
                        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> trapCommand() {
        return Commands.literal("trap")
                .then(
                        Commands.literal("list")
                                .then(
                                        target().executes(
                                                        ctx -> {
                                                            Player p = player(ctx);
                                                            listTraps(
                                                                    ctx.getSource().getSender(),
                                                                    p.getUniqueId(),
                                                                    p.getName());
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        Commands.literal("calc")
                                .then(
                                        Commands.argument("id", LongArgumentType.longArg(1))
                                                .executes(
                                                        ctx -> {
                                                            CommandSender sender =
                                                                    ctx.getSource().getSender();
                                                            long id =
                                                                    LongArgumentType.getLong(
                                                                            ctx, "id");
                                                            var _ =
                                                                    fishing.trapManager()
                                                                            .adminCalc(id)
                                                                            .thenAccept(
                                                                                    calc ->
                                                                                            api.messages()
                                                                                                    .send(
                                                                                                            sender,
                                                                                                            calc
                                                                                                                            .isPresent()
                                                                                                                    ? "fishing.admin.trap_calc"
                                                                                                                    : "fishing.admin.trap_offline",
                                                                                                            Placeholder
                                                                                                                    .unparsed(
                                                                                                                            "id",
                                                                                                                            String
                                                                                                                                    .valueOf(
                                                                                                                                            id)),
                                                                                                            Placeholder
                                                                                                                    .unparsed(
                                                                                                                            "count",
                                                                                                                            String
                                                                                                                                    .valueOf(
                                                                                                                                            calc.map(
                                                                                                                                                            c ->
                                                                                                                                                                    c
                                                                                                                                                                            .produced())
                                                                                                                                                    .orElse(
                                                                                                                                                            0)))));
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        Commands.literal("remove")
                                .then(
                                        Commands.argument("id", LongArgumentType.longArg(1))
                                                .executes(
                                                        ctx -> {
                                                            CommandSender sender =
                                                                    ctx.getSource().getSender();
                                                            long id =
                                                                    LongArgumentType.getLong(
                                                                            ctx, "id");
                                                            var _ =
                                                                    fishing.trapManager()
                                                                            .adminRemove(id)
                                                                            .thenAccept(
                                                                                    ok ->
                                                                                            api.messages()
                                                                                                    .send(
                                                                                                            sender,
                                                                                                            ok
                                                                                                                    ? "fishing.admin.trap_removed"
                                                                                                                    : "fishing.admin.trap_missing",
                                                                                                            Placeholder
                                                                                                                    .unparsed(
                                                                                                                            "id",
                                                                                                                            String
                                                                                                                                    .valueOf(
                                                                                                                                            id))));
                                                            return Command.SINGLE_SUCCESS;
                                                        })));
    }

    /** 통발 목록 (/통발, /harbor fishing trap list). */
    void listTraps(CommandSender sender, UUID owner, String name) {
        var _ =
                fishing.traps()
                        .traps(owner)
                        .thenAccept(
                                list -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "fishing.trap.list_header",
                                                    Placeholder.unparsed("player", name),
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(list.size())));
                                    int retrieveDays = fishing.balance().trapRetrieveDays();
                                    Instant now = Instant.now();
                                    for (TrapInfo t : list) {
                                        Instant next = t.nextCycleAt();
                                        long cycleMin =
                                                next == null
                                                        ? 0
                                                        : Math.max(
                                                                0,
                                                                Duration.between(now, next)
                                                                        .toMinutes());
                                        long retrieveH =
                                                Math.max(
                                                        0,
                                                        Duration.between(
                                                                        now,
                                                                        t.lastHarvest()
                                                                                .plus(
                                                                                        Duration
                                                                                                .ofDays(
                                                                                                        retrieveDays)))
                                                                .toHours());
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "fishing.trap.list_line",
                                                        Placeholder.unparsed(
                                                                "id", String.valueOf(t.id())),
                                                        Placeholder.unparsed(
                                                                "region", regionName(t.region())),
                                                        Placeholder.unparsed(
                                                                "pos",
                                                                t.x() + " " + t.y() + " " + t.z()),
                                                        Placeholder.unparsed(
                                                                "minutes",
                                                                String.valueOf(cycleMin)),
                                                        Placeholder.unparsed(
                                                                "bait", String.valueOf(t.bait())),
                                                        Placeholder.unparsed(
                                                                "stored",
                                                                String.valueOf(t.stored())),
                                                        Placeholder.unparsed(
                                                                "hours",
                                                                String.valueOf(retrieveH)));
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    api.messages().send(sender, "fishing.trap.db_down");
                                    return null;
                                });
    }

    private String regionName(RegionId region) {
        return api.content()
                .snapshot()
                .get(RegionDef.TYPE, region.id())
                .map(RegionDef::name)
                .orElse(region.toString());
    }

    private LiteralArgumentBuilder<CommandSourceStack> eventCommand() {
        return Commands.literal("event")
                .then(
                        Commands.literal("list")
                                .executes(
                                        ctx -> {
                                            api.messages()
                                                    .send(
                                                            ctx.getSource().getSender(),
                                                            "fishing.admin.events",
                                                            Placeholder.unparsed(
                                                                    "events",
                                                                    String.join(
                                                                            ", ",
                                                                            fishing
                                                                                    .activeSeaEvents())));
                                            return Command.SINGLE_SUCCESS;
                                        }))
                .then(
                        Commands.literal("start")
                                .then(
                                        Commands.argument("preset", StringArgumentType.word())
                                                .suggests(
                                                        (ctx, b) -> {
                                                            api.content()
                                                                    .snapshot()
                                                                    .single(SeaEventPresets.TYPE)
                                                                    .events()
                                                                    .keySet()
                                                                    .forEach(b::suggest);
                                                            return b.buildFuture();
                                                        })
                                                .executes(this::startEvent)))
                .then(
                        Commands.literal("stop")
                                .then(
                                        Commands.argument("event", StringArgumentType.word())
                                                .suggests(
                                                        (ctx, b) -> {
                                                            fishing.activeSeaEvents()
                                                                    .forEach(b::suggest);
                                                            return b.buildFuture();
                                                        })
                                                .executes(
                                                        ctx -> {
                                                            String id =
                                                                    StringArgumentType.getString(
                                                                            ctx, "event");
                                                            api.bus()
                                                                    .publish(
                                                                            SeaEventMessage.CHANNEL,
                                                                            new SeaEventMessage(
                                                                                    id,
                                                                                    SeaEventMessage
                                                                                            .State
                                                                                            .END,
                                                                                    null,
                                                                                    null,
                                                                                    List.of()));
                                                            fishing.seaEvents()
                                                                    .apply(
                                                                            new SeaEventMessage(
                                                                                    id,
                                                                                    SeaEventMessage
                                                                                            .State
                                                                                            .END,
                                                                                    null,
                                                                                    null,
                                                                                    List.of()),
                                                                            Instant.now());
                                                            api.messages()
                                                                    .send(
                                                                            ctx.getSource()
                                                                                    .getSender(),
                                                                            "fishing.admin.event_stopped",
                                                                            Placeholder.unparsed(
                                                                                    "event", id));
                                                            return Command.SINGLE_SUCCESS;
                                                        })));
    }

    /** 해상 이벤트 시험: 프리셋을 harbor:event로 발행한다 (실행 ID = 프리셋_시각, 신화 카운터가 실행마다 따로). */
    private int startEvent(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String presetId = StringArgumentType.getString(ctx, "preset");
        Optional<SeaEventPresets.Preset> preset =
                api.content().snapshot().single(SeaEventPresets.TYPE).get(presetId);
        if (preset.isEmpty()) {
            api.messages().send(sender, "fishing.admin.unknown_event");
            return 0;
        }
        Instant now = Instant.now();
        String runId = presetId + "_" + now.getEpochSecond();
        SeaEventMessage message =
                new SeaEventMessage(
                        runId,
                        SeaEventMessage.State.START,
                        preset.get().region(),
                        now.plus(preset.get().duration()),
                        preset.get().fishEffects());
        api.bus().publish(SeaEventMessage.CHANNEL, message);
        // Redis가 없거나 늦어도 이 서버에는 바로 반영 (같은 ID라 중복 반영돼도 덮어씀)
        fishing.seaEvents().apply(message, now);
        api.messages()
                .send(sender, "fishing.admin.event_started", Placeholder.unparsed("event", runId));
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> foodCommand() {
        return Commands.literal("food")
                .then(
                        target().executes(
                                        ctx -> {
                                            Player p = player(ctx);
                                            ActiveFood active = food.active(p);
                                            api.messages()
                                                    .send(
                                                            ctx.getSource().getSender(),
                                                            "fishing.admin.food",
                                                            Placeholder.unparsed(
                                                                    "player", p.getName()),
                                                            Placeholder.unparsed(
                                                                    "food",
                                                                    active == null
                                                                            ? "-"
                                                                            : active.item()
                                                                                    + " ~"
                                                                                    + active
                                                                                            .expiresAt()));
                                            return Command.SINGLE_SUCCESS;
                                        }));
    }

    // ------------------------------------------------------------------ 플레이어 명령

    /** /낚시 설정, /통발 (docs 20 3.5). */
    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("낚시")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .then(
                                Commands.literal("설정")
                                        .executes(
                                                ctx -> {
                                                    if (ctx.getSource().getExecutor()
                                                            instanceof Player p) {
                                                        settings(p);
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                }))
                        .build(),
                "낚시 설정",
                List.of());
        commands.register(
                Commands.literal("통발")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(
                                ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player p) {
                                        listTraps(p, p.getUniqueId(), p.getName());
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        .build(),
                "내 통발 목록",
                List.of());
    }

    /** 설정 화면: 누른 항목을 바꾸고 다시 연다. */
    private void settings(Player player) {
        FishingSettings s = FishingSettings.of(api.flags(), player.getUniqueId());
        MessageService msg = api.messages();
        List<Dialogs.Choice> choices =
                List.of(
                        new Dialogs.Choice(
                                msg.get(
                                        s.lookMode()
                                                ? "fishing.settings.direction_look"
                                                : "fishing.settings.direction_move"),
                                msg.get("fishing.settings.direction_tip")),
                        new Dialogs.Choice(
                                msg.get(
                                        s.dialog()
                                                ? "fishing.settings.dialog_on"
                                                : "fishing.settings.dialog_off"),
                                msg.get("fishing.settings.dialog_tip")),
                        new Dialogs.Choice(
                                msg.get("fishing.settings.intensity_" + s.intensity()),
                                msg.get("fishing.settings.intensity_tip")),
                        new Dialogs.Choice(msg.get("fishing.settings.close"), Component.empty()));
        api.dialogs()
                .choose(
                        player,
                        msg.get("fishing.settings.title"),
                        List.of(msg.get("fishing.settings.body")),
                        choices,
                        1,
                        index -> {
                            FishingSettings next =
                                    switch (index) {
                                        case 0 ->
                                                new FishingSettings(
                                                        !s.lookMode(), s.dialog(), s.intensity());
                                        case 1 ->
                                                new FishingSettings(
                                                        s.lookMode(), !s.dialog(), s.intensity());
                                        case 2 ->
                                                new FishingSettings(
                                                        s.lookMode(),
                                                        s.dialog(),
                                                        (s.intensity() + 2) % 3);
                                        default -> null;
                                    };
                            if (next != null) {
                                var _ =
                                        FishingSettings.save(
                                                        api.flags(), player.getUniqueId(), next)
                                                .thenRun(() -> settings(player));
                            }
                        });
    }

    // ------------------------------------------------------------------ 도우미

    private static String percent(Map<Grade, Integer> grades, int total) {
        StringBuilder sb = new StringBuilder();
        for (Grade grade : Grade.values()) {
            int n = grades.getOrDefault(grade, 0);
            if (n > 0) {
                sb.append(grade.key())
                        .append(' ')
                        .append(
                                String.format(
                                        Locale.ROOT, "%.2f%%", 100.0 * n / Math.max(1, total)))
                        .append("  ");
            }
        }
        return sb.toString().strip();
    }

    private RequiredArgumentBuilder<CommandSourceStack, Key> fishArgument() {
        return Commands.argument("fish", ArgumentTypes.key())
                .suggests(
                        (ctx, b) -> {
                            api.content()
                                    .snapshot()
                                    .ids(FishDef.TYPE.name())
                                    .forEach(id -> b.suggest(id.toString()));
                            return b.buildFuture();
                        });
    }

    private Optional<FishDef> fish(CommandContext<CommandSourceStack> ctx) {
        try {
            return api.content()
                    .snapshot()
                    .get(FishDef.TYPE, harborId(ctx.getArgument("fish", Key.class)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver>
            target() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    private static Player player(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }
}
