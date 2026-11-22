package wiki.creeper.harbor.quest.ui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/** Handlers for the /harbor quest admin subcommands. Every change is recorded in admin_log. */
final class QuestAdminActions {

    private final HarborApi api;
    private final QuestServices services;
    private final AdminLog adminLog;
    private final QuestCommandArgs args;

    QuestAdminActions(
            HarborApi api, QuestServices services, AdminLog adminLog, QuestCommandArgs args) {
        this.api = api;
        this.services = services;
        this.adminLog = adminLog;
        this.args = args;
    }

    private QuestEngine engine() {
        return services.engine();
    }

    int info(CommandContext<CommandSourceStack> ctx, boolean one) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = QuestCommandArgs.player(ctx);
        Optional<QuestDef> filter = one ? args.quest(ctx) : Optional.empty();
        var uuid = target.getUniqueId();
        var _ =
                api.database()
                        .async(c -> QuestRepository.rows(c, uuid))
                        .thenAcceptAsync(
                                rows -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "quest.admin.info_header",
                                                    Placeholder.unparsed(
                                                            "player", target.getName()),
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(rows.size())));
                                    for (QuestRepository.Row row : rows) {
                                        if (filter.isPresent()
                                                && !filter.get().id().equals(row.quest())) {
                                            continue;
                                        }
                                        api.messages()
                                                .send(
                                                        sender,
                                                        one
                                                                ? "quest.admin.info_detail"
                                                                : "quest.admin.info_line",
                                                        Placeholder.unparsed(
                                                                "quest", row.quest().toString()),
                                                        Placeholder.unparsed(
                                                                "season",
                                                                String.valueOf(row.season())),
                                                        Placeholder.unparsed("state", row.state()),
                                                        Placeholder.unparsed(
                                                                "progress", row.progress()),
                                                        Placeholder.unparsed(
                                                                "updated", row.updatedAt()));
                                    }
                                    if (one) {
                                        filter.ifPresent(
                                                d ->
                                                        api.messages()
                                                                .send(
                                                                        sender,
                                                                        "quest.admin.info_state",
                                                                        Placeholder.unparsed(
                                                                                "state",
                                                                                engine().state(
                                                                                                uuid,
                                                                                                d
                                                                                                        .id())
                                                                                        .key())));
                                    }
                                },
                                engine().mainExecutor());
        return Command.SINGLE_SUCCESS;
    }

    int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = QuestCommandArgs.player(ctx);
        Optional<QuestDef> def = args.quest(ctx);
        String raw = StringArgumentType.getString(ctx, "state").toLowerCase(Locale.ROOT);
        if (def.isEmpty() || !QuestCommands.STATES.contains(raw)) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        var _ =
                engine().adminSet(
                                target,
                                def.get(),
                                QuestState.valueOf(raw.toUpperCase(Locale.ROOT)));
        done(ctx, "set", target, def.get().id() + " " + raw);
        return Command.SINGLE_SUCCESS;
    }

    int progress(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = QuestCommandArgs.player(ctx);
        Optional<QuestDef> def = args.quest(ctx);
        String key = StringArgumentType.getString(ctx, "key");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        if (def.isEmpty() || !engine().adminProgress(target, def.get(), key, value)) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        done(ctx, "progress", target, def.get().id() + " " + key + "=" + value);
        return Command.SINGLE_SUCCESS;
    }

    int complete(CommandContext<CommandSourceStack> ctx, boolean reward)
            throws CommandSyntaxException {
        Player target = QuestCommandArgs.player(ctx);
        Optional<QuestDef> def = args.quest(ctx);
        if (def.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        HarborPlayer hp = api.players().get(target);
        Optional<QuestEntry> entry = hp.get(QuestEngine.COMPONENT).entry(def.get().id());
        if (entry.isEmpty() || !entry.get().state().open()) {
            // if not in progress, only set the state to COMPLETED (no rewards)
            var _ = engine().adminSet(target, def.get(), QuestState.COMPLETED);
        } else {
            var _ = services.forceComplete(target, def.get(), reward);
        }
        done(ctx, "complete", target, def.get().id() + (reward ? "" : " --no-reward"));
        return Command.SINGLE_SUCCESS;
    }

    int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = QuestCommandArgs.player(ctx);
        Optional<QuestDef> def = args.quest(ctx);
        QuestId id = def.map(QuestDef::id).orElseGet(() -> QuestCommandArgs.rawQuest(ctx));
        var _ = engine().adminReset(target, id);
        done(ctx, "reset", target, id.toString());
        return Command.SINGLE_SUCCESS;
    }

    int reroll(CommandContext<CommandSourceStack> ctx, String kindKey)
            throws CommandSyntaxException {
        Player target = QuestCommandArgs.player(ctx);
        Optional<QuestType> kind =
                wiki.creeper.harbor.common.model.Keyed.fromKey(QuestType.class, kindKey)
                        .filter(QuestType::board);
        if (kind.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        var _ = services.board().ensure(target, kind.get(), true);
        done(ctx, "board.reroll", target, kind.get().key());
        return Command.SINGLE_SUCCESS;
    }

    int server(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                services.serverProgress()
                        .climaxStatus()
                        .thenAccept(
                                s ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "quest.admin.server",
                                                        Placeholder.unparsed(
                                                                "rate",
                                                                String.format(
                                                                        Locale.ROOT,
                                                                        "%.1f",
                                                                        s.donationRate() * 100)),
                                                        Placeholder.unparsed(
                                                                "need_rate",
                                                                String.format(
                                                                        Locale.ROOT,
                                                                        "%.1f",
                                                                        s.requiredRate() * 100)),
                                                        Placeholder.unparsed(
                                                                "clears",
                                                                String.valueOf(s.clears())),
                                                        Placeholder.unparsed(
                                                                "need_clears",
                                                                String.valueOf(s.requiredClears())),
                                                        Placeholder.unparsed(
                                                                "active",
                                                                String.valueOf(s.activePlayers())),
                                                        Placeholder.unparsed(
                                                                "ready", String.valueOf(s.ready())),
                                                        Placeholder.unparsed(
                                                                "flagged",
                                                                String.valueOf(s.flagged()))));
        return Command.SINGLE_SUCCESS;
    }

    void done(CommandContext<CommandSourceStack> ctx, String action, Player target, String detail) {
        api.messages().send(ctx.getSource().getSender(), "quest.admin.done");
        log(ctx.getSource().getSender(), action, target.getUniqueId().toString(), detail);
    }

    void log(CommandSender sender, String action, String target, String detail) {
        adminLog.log(sender, action, target, detail);
    }
}
