package wiki.creeper.harbor.core.furniture;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.content.type.FurnitureDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * 가구·커스텀 블록 관리 명령 (권한 harbor.admin.furniture · harbor.admin.block, 추가 제안).
 *
 * <pre>
 * /harbor furniture list
 * /harbor furniture give &lt;대상&gt; &lt;가구&gt; [개수]
 * /harbor block list
 * /harbor block give &lt;대상&gt; &lt;블록&gt; [개수]
 * </pre>
 */
public final class FurnishingCommand {

    private static final int MAX_GIVE = 64 * 9;

    private static final String FURNITURE = "furniture";

    private static final String BLOCK = "block";

    private final FurnitureManager furniture;
    private final CustomBlockManager blocks;
    private final MessageService messages;
    private final AdminLog adminLog;

    public FurnishingCommand(
            FurnitureManager furniture,
            CustomBlockManager blocks,
            MessageService messages,
            AdminLog adminLog) {
        this.furniture = furniture;
        this.blocks = blocks;
        this.messages = messages;
        this.adminLog = adminLog;
    }

    // 하나의 정의 목록에 대한 list · give
    private record Kind<T>(
            String literal,
            Function<HarborId, Optional<T>> find,
            java.util.function.Supplier<List<T>> all,
            Function<T, HarborId> id,
            Function<T, String> name,
            java.util.function.BiFunction<T, Integer, ItemStack> item) {}

    public LiteralArgumentBuilder<CommandSourceStack> furniture() {
        return build(
                new Kind<FurnitureDef>(
                        FURNITURE,
                        furniture::def,
                        furniture::all,
                        FurnitureDef::id,
                        FurnitureDef::name,
                        (def, n) -> {
                            ItemStack stack = furniture.item(def);
                            stack.setAmount(Math.min(n, stack.getMaxStackSize()));
                            return stack;
                        }));
    }

    public LiteralArgumentBuilder<CommandSourceStack> blocks() {
        return build(
                new Kind<CustomBlockDef>(
                        BLOCK,
                        blocks::def,
                        blocks::all,
                        CustomBlockDef::id,
                        CustomBlockDef::name,
                        blocks::item));
    }

    private <T> LiteralArgumentBuilder<CommandSourceStack> build(Kind<T> kind) {
        return Commands.literal(kind.literal())
                .then(Commands.literal("list").executes(ctx -> list(ctx, kind)))
                .then(
                        Commands.literal("give")
                                .then(
                                        Commands.argument("target", ArgumentTypes.player())
                                                .then(
                                                        Commands.argument("id", ArgumentTypes.key())
                                                                .suggests(suggest(kind))
                                                                .executes(ctx -> give(ctx, kind, 1))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "amount",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        1,
                                                                                                        MAX_GIVE))
                                                                                .executes(
                                                                                        ctx ->
                                                                                                give(
                                                                                                        ctx,
                                                                                                        kind,
                                                                                                        IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        ctx,
                                                                                                                        "amount")))))));
    }

    private <T> SuggestionProvider<CommandSourceStack> suggest(Kind<T> kind) {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (T def : kind.all().get()) {
                HarborId id = kind.id().apply(def);
                if (id.toString().startsWith(typed) || id.name().startsWith(typed)) {
                    builder.suggest(
                            id.toString(),
                            MessageComponentSerializer.message()
                                    .serialize(Component.text(kind.name().apply(def))));
                }
            }
            return builder.buildFuture();
        };
    }

    private <T> int list(CommandContext<CommandSourceStack> ctx, Kind<T> kind) {
        CommandSender sender = ctx.getSource().getSender();
        List<T> all = kind.all().get();
        messages.send(
                sender,
                "furniture.admin.list_header",
                Placeholder.unparsed("count", String.valueOf(all.size())));
        for (T def : all) {
            messages.send(
                    sender,
                    "furniture.admin.list_line",
                    Placeholder.unparsed("id", kind.id().apply(def).toString()),
                    Placeholder.unparsed("name", kind.name().apply(def)));
        }
        return all.size();
    }

    private <T> int give(CommandContext<CommandSourceStack> ctx, Kind<T> kind, int amount)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        Key key = ctx.getArgument("id", Key.class);
        Optional<T> def = HarborId.tryParse(key.asString()).flatMap(kind.find());
        if (def.isEmpty()) {
            messages.send(
                    sender, "furniture.admin.unknown", Placeholder.unparsed("id", key.asString()));
            return 0;
        }
        int left = amount;
        while (left > 0) {
            ItemStack stack = kind.item().apply(def.get(), Math.min(left, 64));
            left -= stack.getAmount();
            for (ItemStack rest : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItem(target.getLocation(), rest);
            }
        }
        adminLog.record(
                sender,
                kind.literal() + ".give",
                target.getUniqueId().toString(),
                kind.id().apply(def.get()) + " x" + amount);
        messages.send(
                sender,
                "furniture.admin.given",
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("name", kind.name().apply(def.get())),
                Placeholder.unparsed("amount", String.valueOf(amount)));
        return amount;
    }
}
