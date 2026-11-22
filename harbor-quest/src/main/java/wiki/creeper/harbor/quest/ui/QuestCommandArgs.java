package wiki.creeper.harbor.quest.ui;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/** Shared quest and target player arguments for the quest commands. */
final class QuestCommandArgs {

    private final HarborApi api;
    private final QuestServices services;

    QuestCommandArgs(HarborApi api, QuestServices services) {
        this.api = api;
        this.services = services;
    }

    private QuestEngine engine() {
        return services.engine();
    }

    RequiredArgumentBuilder<CommandSourceStack, Key> questArgument(boolean activeOnly) {
        return Commands.argument("quest", ArgumentTypes.key())
                .suggests(
                        (ctx, b) -> {
                            if (activeOnly && ctx.getSource().getExecutor() instanceof Player p) {
                                api.players()
                                        .get(p.getUniqueId())
                                        .ifPresent(
                                                hp ->
                                                        engine().open(hp)
                                                                .forEach(
                                                                        e ->
                                                                                b.suggest(
                                                                                        e.getKey()
                                                                                                .id()
                                                                                                .toString())));
                            } else {
                                engine().defs().forEach(d -> b.suggest(d.id().toString()));
                            }
                            return b.buildFuture();
                        });
    }

    Optional<QuestDef> quest(CommandContext<CommandSourceStack> ctx) {
        return engine().def(rawQuest(ctx));
    }

    static QuestId rawQuest(CommandContext<CommandSourceStack> ctx) {
        Key key = ctx.getArgument("quest", Key.class);
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        try {
            HarborId id = new HarborId(namespace, key.value());
            return id.hasPrefix(QuestId.PREFIX)
                    ? new QuestId(id)
                    : new QuestId(HarborId.parse("quest/invalid"));
        } catch (IllegalArgumentException e) {
            return new QuestId(HarborId.parse("quest/invalid"));
        }
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
