package wiki.creeper.harbor.fishing.session;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import net.kyori.adventure.key.Key;
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
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.food.FoodManager;
import wiki.creeper.harbor.fishing.roll.RollEngine;

/** /harbor fishing … (docs 20 3.5). Core applies the harbor.admin.fishing permission. */
public final class FishingCommands {

    private final HarborApi api;
    private final FishingManager fishing;
    private final FishingAdminSubcommands subcommands;
    private final FishingPlayerCommands playerCommands;

    public FishingCommands(HarborApi api, FishingManager fishing, FoodManager food) {
        this.api = api;
        this.fishing = fishing;
        this.subcommands = new FishingAdminSubcommands(api, fishing, food);
        this.playerCommands = new FishingPlayerCommands(api, subcommands);
    }

    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return baseAdmin()
                .then(subcommands.statsCommand())
                .then(subcommands.trapCommand())
                .then(subcommands.eventCommand())
                .then(subcommands.foodCommand());
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

    // ------------------------------------------------------------------ Player commands

    /** /낚시 설정, /통발 (docs 20 3.5). */
    public void registerPlayerCommands(Commands commands) {
        playerCommands.register(commands);
    }

    // ------------------------------------------------------------------ Helpers

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

    static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver> target() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    static Player player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }
}
