package wiki.creeper.harbor.fishing.session;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;

/** Player commands /낚시 설정 and /통발 (docs 20 3.5), split out of {@link FishingCommands}. */
final class FishingPlayerCommands {

    private final HarborApi api;
    private final FishingAdminSubcommands subcommands;

    FishingPlayerCommands(HarborApi api, FishingAdminSubcommands subcommands) {
        this.api = api;
        this.subcommands = subcommands;
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
                        .build(),
                "낚시 설정",
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
