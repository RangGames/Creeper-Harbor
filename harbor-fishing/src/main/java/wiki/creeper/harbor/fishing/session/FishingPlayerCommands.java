package wiki.creeper.harbor.fishing.session;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.FishEarnings;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.data.DailyCatch;

/**
 * Player commands /낚시 설정, /통발 (docs 20 3.5) and /미끼 (proposed), split out of {@link
 * FishingCommands}.
 */
final class FishingPlayerCommands {

    private final HarborApi api;
    private final Tasks tasks;
    private final FishingAdminSubcommands subcommands;
    private final BaitMenu baitMenu;

    FishingPlayerCommands(
            HarborApi api, Tasks tasks, FishingAdminSubcommands subcommands, BaitMenu baitMenu) {
        this.api = api;
        this.tasks = tasks;
        this.subcommands = subcommands;
        this.baitMenu = baitMenu;
    }

    void register(Commands commands) {
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
                        .then(
                                Commands.literal("요약")
                                        .executes(
                                                ctx -> {
                                                    if (ctx.getSource().getExecutor()
                                                            instanceof Player p) {
                                                        summary(p);
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                }))
                        .build(),
                "낚시 설정 · 오늘의 어획 요약",
                List.of());
        commands.register(
                Commands.literal("통발")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(
                                ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player p) {
                                        subcommands.listTraps(p, p.getUniqueId(), p.getName());
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        .build(),
                "내 통발 목록",
                List.of());
        commands.register(
                Commands.literal("미끼")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(
                                ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player p) {
                                        baitMenu.open(p);
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        .build(),
                "낚시에 쓸 미끼 고르기",
                List.of());
    }

    /**
     * Today's catch summary ("4. Fishing presentation" 1.2 · 1.3 quick action): number of catches,
     * best grade and biggest fish of the day.
     */
    void summary(Player player) {
        MessageService msg = api.messages();
        FishingBalance balance = new FishingBalance(api.content().snapshot().single(Balance.TYPE));
        DailyCatch stored = api.players().get(player).get(FishingManager.COMPONENT).daily();
        DailyCatch today =
                stored == null ? DailyCatch.empty(balance.today()) : stored.asOf(balance.today());
        List<Component> body = new ArrayList<>();
        body.add(
                msg.get(
                        "fishing.summary.count",
                        Placeholder.unparsed("count", String.valueOf(today.catches()))));
        Grade best = today.best();
        if (best != null) {
            body.add(
                    msg.get(
                            "fishing.summary.best",
                            Placeholder.component(
                                    "grade", msg.get("collection.grade." + best.key()))));
        }
        FishId big = today.bigFish();
        if (big != null) {
            String name =
                    api.content()
                            .snapshot()
                            .get(FishDef.TYPE, big.id())
                            .map(FishDef::name)
                            .orElse(big.toString());
            body.add(
                    msg.get(
                            "fishing.summary.biggest",
                            Placeholder.unparsed("name", name),
                            Placeholder.unparsed(
                                    "size", String.format(Locale.ROOT, "%.1f", today.bigCm()))));
        }
        if (today.catches() == 0) {
            body.add(msg.get("fishing.summary.none"));
        }
        // Fish sale earnings from Economy (ledger, async); left out without Economy or on error
        Optional<FishEarnings> earnings = api.service(FishEarnings.class);
        if (earnings.isEmpty()) {
            api.dialogs().notice(player, msg.get("fishing.summary.title"), body);
            return;
        }
        Instant since = today.day().atStartOfDay(balance.dailyZone()).toInstant();
        var _ =
                earnings.get()
                        .fishSales(player.getUniqueId(), since)
                        .handleAsync(
                                (gold, error) -> {
                                    if (!player.isOnline()) {
                                        return null;
                                    }
                                    if (error == null && gold != null) {
                                        body.add(
                                                msg.get(
                                                        "fishing.summary.earnings",
                                                        Placeholder.unparsed(
                                                                "gold",
                                                                String.format(
                                                                        Locale.ROOT,
                                                                        "%,d",
                                                                        gold))));
                                    }
                                    api.dialogs()
                                            .notice(player, msg.get("fishing.summary.title"), body);
                                    return null;
                                },
                                tasks.main());
    }

    /** Settings screen: toggles the clicked item and reopens it. */
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
}
