package wiki.creeper.harbor.fishing.session;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.fishing.api.TrapInfo;
import wiki.creeper.harbor.fishing.content.SeaEventPresets;
import wiki.creeper.harbor.fishing.food.ActiveFood;
import wiki.creeper.harbor.fishing.food.FoodManager;

/**
 * The stats, trap, event and food subtrees of /harbor fishing (docs 20 3.5), split out of {@link
 * FishingCommands}.
 */
final class FishingAdminSubcommands {

    private final HarborApi api;
    private final FishingManager fishing;
    private final FoodManager food;

    FishingAdminSubcommands(HarborApi api, FishingManager fishing, FoodManager food) {
        this.api = api;
        this.fishing = fishing;
        this.food = food;
    }

    LiteralArgumentBuilder<CommandSourceStack> statsCommand() {
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

    LiteralArgumentBuilder<CommandSourceStack> trapCommand() {
        return Commands.literal("trap")
                .then(
                        Commands.literal("list")
                                .then(
                                        FishingCommands.target()
                                                .executes(
                                                        ctx -> {
                                                            Player p = FishingCommands.player(ctx);
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

    /** Trap list (/통발, /harbor fishing trap list). */
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

    LiteralArgumentBuilder<CommandSourceStack> eventCommand() {
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

    /**
     * Sea event test: publishes a preset over harbor:event (run ID = preset_time, so the mythic
     * counter is separate per run).
     */
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
        // Apply on this server right away even if Redis is missing or slow (same ID, so a duplicate
        // apply just overwrites)
        fishing.seaEvents().apply(message, now);
        api.messages()
                .send(sender, "fishing.admin.event_started", Placeholder.unparsed("event", runId));
        return Command.SINGLE_SUCCESS;
    }

    LiteralArgumentBuilder<CommandSourceStack> foodCommand() {
        return Commands.literal("food")
                .then(
                        FishingCommands.target()
                                .executes(
                                        ctx -> {
                                            Player p = FishingCommands.player(ctx);
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
}
