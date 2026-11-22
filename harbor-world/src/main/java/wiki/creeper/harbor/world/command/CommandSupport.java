package wiki.creeper.harbor.world.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.SeaEventDef;

/** Shared argument builders and result helpers for the World commands. */
final class CommandSupport {

    private final WorldKit kit;

    CommandSupport(WorldKit kit) {
        this.kit = kit;
    }

    static java.util.function.Predicate<CommandSourceStack> perm(String sub) {
        return src -> src.getSender().hasPermission("harbor.admin.world." + sub);
    }

    RequiredArgumentBuilder<CommandSourceStack, String> regionArg() {
        return Commands.argument("region", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            kit.index().regions().forEach(e -> b.suggest(e.id().id().name()));
                            return b.buildFuture();
                        });
    }

    RequiredArgumentBuilder<CommandSourceStack, String> eventArg() {
        return Commands.argument("event", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            kit.api()
                                    .content()
                                    .snapshot()
                                    .all(SeaEventDef.TYPE)
                                    .keySet()
                                    .forEach(id -> b.suggest(id.name()));
                            return b.buildFuture();
                        });
    }

    Optional<RegionId> region(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "region");
        return HarborId.tryParse(raw.contains("/") ? raw : "region/" + raw)
                .filter(id -> id.hasPrefix(RegionId.PREFIX))
                .map(RegionId::new)
                .filter(id -> kit.index().get(id).isPresent());
    }

    static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver> target() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    static Player targetPlayer(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }

    int done(CommandContext<CommandSourceStack> ctx, boolean ok) {
        kit.api()
                .messages()
                .send(ctx.getSource().getSender(), ok ? "world.admin.done" : "world.admin.failed");
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    static int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            action.accept(p);
            return Command.SINGLE_SUCCESS;
        }
        return 0;
    }
}
