package wiki.creeper.harbor.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.Messages;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.ItemService;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.ModifierService;
import wiki.creeper.harbor.core.api.PlayerService;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.content.HarborContent;

/**
 * /harbor 관리 명령 ('16. 플러그인 설계' 5.8). 권한은 harbor.admin.<하위 명령>이고, 모든 실행을 admin_log에 남긴다. 모듈은 {@link
 * #addModuleCommand}로 하위 명령을 더한다.
 */
public final class HarborCommand {

    /** 리로드할 때 한꺼번에 다시 읽는 형식 묶음. */
    private static final List<String> RELOAD_SCOPES =
            List.of("all", "content", "balance", "messages");

    private static final int MAX_ERRORS_SHOWN = 20;

    private final HarborContent content;
    private final ItemService items;
    private final ModifierService modifiers;
    private final PlayerService players;
    private final CurrencyService currency;
    private final MessageService messages;
    private final Database db;
    private final WriteBehindQueue queue;
    private final AdminLog adminLog;
    private final List<LiteralArgumentBuilder<CommandSourceStack>> moduleCommands =
            new CopyOnWriteArrayList<>();

    public HarborCommand(
            HarborContent content,
            ItemService items,
            ModifierService modifiers,
            PlayerService players,
            CurrencyService currency,
            MessageService messages,
            Database db,
            WriteBehindQueue queue,
            AdminLog adminLog) {
        this.content = content;
        this.items = items;
        this.modifiers = modifiers;
        this.players = players;
        this.currency = currency;
        this.messages = messages;
        this.db = db;
        this.queue = queue;
        this.adminLog = adminLog;
    }

    public void addModuleCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        moduleCommands.add(command.requires(permission(command.getLiteral())));
    }

    /** LifecycleEvents.COMMANDS에서 등록한다 (모든 모듈이 켜진 뒤라 모듈 하위 명령도 들어 있다). */
    public void register(Commands commands) {
        commands.register(build(), "크리퍼 항구 관리 명령", List.of());
    }

    private LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("harbor")
                        .requires(src -> src.getSender().hasPermission("harbor.admin"));

        LiteralArgumentBuilder<CommandSourceStack> reload =
                sub("reload").executes(ctx -> reload(ctx, "all"));
        for (String scope : RELOAD_SCOPES) {
            reload.then(Commands.literal(scope).executes(ctx -> reload(ctx, scope)));
        }
        root.then(reload);
        root.then(sub("validate").executes(this::validate));

        root.then(
                sub("give")
                        .then(
                                Commands.argument("target", ArgumentTypes.player())
                                        .then(
                                                Commands.argument("item", ArgumentTypes.key())
                                                        .suggests(
                                                                (ctx, builder) -> {
                                                                    ContentSnapshot snap =
                                                                            content.snapshot();
                                                                    String typed =
                                                                            builder.getRemaining()
                                                                                    .toLowerCase(
                                                                                            Locale
                                                                                                    .ROOT);
                                                                    for (HarborId id :
                                                                            snap.ids(
                                                                                    ItemDef.TYPE
                                                                                            .name())) {
                                                                        suggest(builder, id, typed);
                                                                    }
                                                                    for (HarborId id :
                                                                            snap.ids(
                                                                                    FishDef.TYPE
                                                                                            .name())) {
                                                                        suggest(builder, id, typed);
                                                                    }
                                                                    return builder.buildFuture();
                                                                })
                                                        .executes(ctx -> give(ctx, 1, null, null))
                                                        .then(
                                                                Commands.argument(
                                                                                "amount",
                                                                                IntegerArgumentType
                                                                                        .integer(
                                                                                                1,
                                                                                                64))
                                                                        .executes(
                                                                                ctx ->
                                                                                        give(
                                                                                                ctx,
                                                                                                IntegerArgumentType
                                                                                                        .getInteger(
                                                                                                                ctx,
                                                                                                                "amount"),
                                                                                                null,
                                                                                                null)))
                                                        .then(
                                                                Commands.argument(
                                                                                "size",
                                                                                DoubleArgumentType
                                                                                        .doubleArg(
                                                                                                0.1))
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
                                                                                                        give(
                                                                                                                ctx,
                                                                                                                1,
                                                                                                                DoubleArgumentType
                                                                                                                        .getDouble(
                                                                                                                                ctx,
                                                                                                                                "size"),
                                                                                                                IntegerArgumentType
                                                                                                                        .getInteger(
                                                                                                                                ctx,
                                                                                                                                "quality"))))))));

        root.then(
                sub("stat")
                        .then(
                                Commands.argument("target", ArgumentTypes.player())
                                        .then(
                                                Commands.argument("stat", StringArgumentType.word())
                                                        .suggests(
                                                                (ctx, builder) -> {
                                                                    content.snapshot()
                                                                            .single(Balance.TYPE)
                                                                            .keys("stats")
                                                                            .forEach(
                                                                                    builder
                                                                                            ::suggest);
                                                                    return builder.buildFuture();
                                                                })
                                                        .executes(this::stat))));

        root.then(
                sub("player")
                        .then(
                                Commands.argument("target", ArgumentTypes.player())
                                        .executes(this::player)));

        LiteralArgumentBuilder<CommandSourceStack> currencyCmd = sub("currency");
        for (Currency c : List.of(Currency.GOLD, Currency.MULBINEUL)) {
            for (boolean give : new boolean[] {true, false}) {
                currencyCmd.then(
                        Commands.argument("target", ArgumentTypes.player())
                                .then(
                                        Commands.literal(give ? "give" : "take")
                                                .then(
                                                        Commands.literal(c.key())
                                                                .then(
                                                                        Commands.argument(
                                                                                        "amount",
                                                                                        LongArgumentType
                                                                                                .longArg(
                                                                                                        1))
                                                                                .executes(
                                                                                        ctx ->
                                                                                                currency(
                                                                                                        ctx,
                                                                                                        c,
                                                                                                        give))))));
            }
        }
        root.then(currencyCmd);

        root.then(sub("debug").executes(this::debug));

        moduleCommands.forEach(root::then);
        return root.build();
    }

    // ------------------------------------------------------------------ 하위 명령

    private int reload(CommandContext<CommandSourceStack> ctx, String scope) {
        CommandSender sender = ctx.getSource().getSender();
        Set<String> types =
                switch (scope) {
                    case "balance" -> Set.of(Balance.TYPE.name());
                    case "messages" -> Set.of(Messages.TYPE.name());
                    case "content" -> withoutBalanceAndMessages();
                    default -> Set.of();
                };
        adminLog.record(sender, "reload", null, scope);
        var _ =
                content.reload(types, true)
                        .thenAccept(result -> report(sender, result, "command.reload.ok"));
        return Command.SINGLE_SUCCESS;
    }

    private int validate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        adminLog.record(sender, "validate", null, null);
        var _ =
                content.reload(Set.of(), false)
                        .thenAccept(result -> report(sender, result, "command.validate.ok"));
        return Command.SINGLE_SUCCESS;
    }

    private void report(
            CommandSender sender, Result<ContentSnapshot, ValidationErrors> result, String okKey) {
        switch (result) {
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snap) ->
                    messages.send(
                            sender,
                            okKey,
                            Placeholder.unparsed("counts", snap.counts().toString()));
            case Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors) -> {
                messages.send(
                        sender,
                        "command.reload.failed",
                        Placeholder.unparsed("count", String.valueOf(errors.size())));
                errors.list().stream()
                        .limit(MAX_ERRORS_SHOWN)
                        .map(ValidationError::toString)
                        .forEach(
                                line ->
                                        messages.send(
                                                sender,
                                                "command.error_line",
                                                Placeholder.unparsed("error", line)));
            }
        }
    }

    private int give(
            CommandContext<CommandSourceStack> ctx,
            int amount,
            @Nullable Double size,
            @Nullable Integer quality)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = target(ctx);
        HarborId id = harborId(ctx.getArgument("item", Key.class));
        ContentSnapshot snap = content.snapshot();
        ItemStack stack;
        Optional<FishDef> fish = snap.get(FishDef.TYPE, id);
        Optional<ItemDef> item = snap.get(ItemDef.TYPE, id);
        if (fish.isPresent()) {
            FishDef def = fish.get();
            double fishSize = size != null ? size : (def.sizeMin() + def.sizeMax()) / 2;
            int stars = quality != null ? quality : Quality.MIN;
            stack =
                    items.createFish(
                            def,
                            fishSize,
                            Quality.of(stars),
                            players.get(target).ref(),
                            Instant.now());
        } else if (item.isPresent()) {
            stack = items.create(item.get(), amount);
        } else {
            messages.send(
                    sender, "command.give.unknown", Placeholder.unparsed("id", id.toString()));
            return 0;
        }
        target.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        adminLog.record(sender, "give", target.getUniqueId().toString(), id + " x" + amount);
        messages.send(
                sender,
                "command.give.ok",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("id", id.toString()));
        return Command.SINGLE_SUCCESS;
    }

    private int stat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = target(ctx);
        StatKey stat;
        try {
            stat = StatKey.of(StringArgumentType.getString(ctx, "stat"));
        } catch (IllegalArgumentException e) {
            messages.send(sender, "command.stat.unknown");
            return 0;
        }
        double base =
                content.snapshot().single(Balance.TYPE).number("stats." + stat.key() + ".base", 0);
        ModifierService.Breakdown breakdown = modifiers.explain(target.getUniqueId(), stat, base);
        adminLog.record(sender, "stat", target.getUniqueId().toString(), stat.key());
        messages.send(
                sender,
                "command.stat.header",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("stat", stat.key()),
                Placeholder.unparsed("base", fmt(breakdown.base())),
                Placeholder.unparsed("result", fmt(breakdown.result())),
                Placeholder.unparsed("min", fmt(breakdown.min())),
                Placeholder.unparsed("max", fmt(breakdown.max())));
        for (Modifier m : breakdown.applied()) {
            messages.send(
                    sender,
                    "command.stat.line",
                    Placeholder.unparsed("source", m.source()),
                    Placeholder.unparsed("op", m.op() == Modifier.Op.ADD ? "+" : "×"),
                    Placeholder.unparsed("value", fmt(m.value())),
                    Placeholder.unparsed(
                            "expires",
                            m.expiresAt() == null ? "-" : String.valueOf(m.expiresAt())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = target(ctx);
        HarborPlayer hp = players.get(target);
        adminLog.record(sender, "player", target.getUniqueId().toString(), null);
        messages.send(
                sender,
                "command.player.summary",
                Placeholder.unparsed("player", hp.name()),
                Placeholder.unparsed("uuid", hp.uuid().toString()),
                Placeholder.unparsed("season", hp.season().toString()),
                Placeholder.unparsed(
                        "job",
                        hp.jobLine().map(Enum::name).orElse("-")
                                + " "
                                + hp.jobTier().level()
                                + "차"),
                Placeholder.unparsed("level", String.valueOf(hp.level())),
                Placeholder.unparsed("gold", String.valueOf(hp.balance(Currency.GOLD))),
                Placeholder.unparsed("mulbineul", String.valueOf(hp.balance(Currency.MULBINEUL))),
                Placeholder.unparsed("reputation", String.valueOf(hp.reputation())),
                Placeholder.unparsed("playtime", hp.playtime().toHours() + "h"));
        return Command.SINGLE_SUCCESS;
    }

    private int currency(CommandContext<CommandSourceStack> ctx, Currency c, boolean give)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = target(ctx);
        long amount = LongArgumentType.getLong(ctx, "amount");
        adminLog.record(
                sender,
                give ? "currency.give" : "currency.take",
                target.getUniqueId().toString(),
                c.key() + " " + amount);
        var future =
                give
                        ? currency.give(
                                target.getUniqueId(), c, amount, LedgerReason.ADMIN_GRANT, null)
                        : currency.take(
                                target.getUniqueId(), c, amount, LedgerReason.ADMIN_TAKE, null);
        var _ =
                future.thenAccept(
                        result -> {
                            switch (result) {
                                case Result.Ok<Long, LedgerError>(Long balance) ->
                                        messages.send(
                                                sender,
                                                "command.currency.ok",
                                                Placeholder.unparsed("player", target.getName()),
                                                Placeholder.unparsed("currency", c.key()),
                                                Placeholder.unparsed(
                                                        "balance", String.valueOf(balance)));
                                case Result.Err<Long, LedgerError>(LedgerError error) ->
                                        messages.send(
                                                sender,
                                                "command.currency.failed",
                                                Placeholder.unparsed("error", error.name()));
                            }
                        });
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Database.PoolStats pool = db.stats();
        WriteBehindQueue.Stats q = queue.stats();
        messages.send(
                sender,
                "command.debug",
                Placeholder.unparsed(
                        "pool", pool.active() + "/" + pool.max() + " (대기 " + pool.waiting() + ")"),
                Placeholder.unparsed(
                        "queue",
                        q.pending() + " (쓴 것 " + q.written() + ", 버린 것 " + q.dropped() + ")"),
                Placeholder.unparsed("players", String.valueOf(players.online().size())),
                Placeholder.unparsed("threads", String.valueOf(Thread.activeCount())));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ 도우미

    private static LiteralArgumentBuilder<CommandSourceStack> sub(String name) {
        return Commands.literal(name).requires(permission(name));
    }

    private static Predicate<CommandSourceStack> permission(String name) {
        return src -> src.getSender().hasPermission("harbor.admin." + name);
    }

    private static Player target(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }

    /** 키 인자는 네임스페이스를 생략하면 minecraft로 읽히므로 harbor로 바꾼다. */
    private static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }

    private static void suggest(SuggestionsBuilder builder, HarborId id, String typed) {
        String full = id.toString();
        if (full.startsWith(typed) || id.path().startsWith(typed)) {
            builder.suggest(full);
        }
    }

    private Set<String> withoutBalanceAndMessages() {
        Set<String> all = new HashSet<>(content.allTypes());
        all.remove(Balance.TYPE.name());
        all.remove(Messages.TYPE.name());
        return all;
    }

    private static String fmt(double value) {
        if (Double.isInfinite(value)) {
            return value > 0 ? "∞" : "-∞";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
