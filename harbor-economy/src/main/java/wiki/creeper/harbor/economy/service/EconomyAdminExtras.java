package wiki.creeper.harbor.economy.service;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Handlers of {@code /harbor market set <fish> <coef> [hours]} and {@code /harbor mail send
 * <player> <item> [count]} (docs 20 7.5, split out of {@link EconomyAdminCommands}).
 */
final class EconomyAdminExtras {

    private final HarborApi api;
    private final MarketEngine market;
    private final MailManager mail;

    EconomyAdminExtras(HarborApi api, MarketEngine market, MailManager mail) {
        this.api = api;
        this.market = market;
        this.mail = mail;
    }

    private int done(CommandContext<CommandSourceStack> ctx, boolean ok) {
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        ok ? "economy.admin.done" : "economy.admin.failed");
        return Command.SINGLE_SUCCESS;
    }

    static FishId fishArg(CommandContext<CommandSourceStack> ctx) {
        return new FishId(EconomyCommands.harborId(ctx.getArgument("fish", Key.class)));
    }

    /** /harbor market set &lt;fish&gt; &lt;coef&gt; [hours]: pinned until the hours pass. */
    int setMarket(CommandContext<CommandSourceStack> ctx, int hours) {
        FishId fish;
        try {
            fish = fishArg(ctx);
        } catch (IllegalArgumentException e) {
            return done(ctx, false);
        }
        if (api.content()
                .snapshot()
                .get(wiki.creeper.harbor.common.content.type.FishDef.TYPE, fish.id())
                .isEmpty()) {
            api.messages()
                    .send(
                            ctx.getSource().getSender(),
                            "economy.market.unknown_fish",
                            Placeholder.unparsed("fish", fish.toString()));
            return 0;
        }
        market.override(
                fish, DoubleArgumentType.getDouble(ctx, "coef"), java.time.Duration.ofHours(hours));
        return done(ctx, true);
    }

    /**
     * /harbor mail send &lt;player&gt; &lt;item&gt; [count]: creates a Harbor item from its
     * definition and mails it (reward resend, docs 20 7.5). Split into full stacks.
     */
    int mailSend(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        HarborId id;
        try {
            id = EconomyCommands.harborId(ctx.getArgument("item", Key.class));
        } catch (IllegalArgumentException e) {
            return done(ctx, false);
        }
        var def =
                api.content()
                        .snapshot()
                        .get(wiki.creeper.harbor.common.content.type.ItemDef.TYPE, id);
        if (def.isEmpty()) {
            api.messages()
                    .send(
                            ctx.getSource().getSender(),
                            "economy.admin.unknown_item",
                            Placeholder.unparsed("item", id.toString()));
            return 0;
        }
        int left = count;
        while (left > 0) {
            org.bukkit.inventory.ItemStack stack = api.items().create(def.get(), 1);
            int amount = Math.min(left, stack.getMaxStackSize());
            stack.setAmount(amount);
            left -= amount;
            var _ = mail.sendItem(target.getUniqueId(), stack, "admin", null);
        }
        return done(ctx, true);
    }
}
