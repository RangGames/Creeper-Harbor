package wiki.creeper.harbor.core.message;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.task.Tasks;

/** Paper Dialog API wrapper. If the API changes, only this class needs fixing. */
@SuppressWarnings("UnstableApiUsage")
public final class DialogManager implements Dialogs {

    private static final ClickCallback.Options ONCE =
            ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();

    private final Tasks tasks;

    public DialogManager(Tasks tasks) {
        this.tasks = tasks;
    }

    private volatile Predicate<UUID> frozen = uuid -> false;

    /**
     * Check for players whose server-transfer snapshot has been taken (PlayerManager.frozen). Set
     * at Core startup.
     */
    public void setFrozen(Predicate<UUID> frozen) {
        this.frozen = frozen;
    }

    /**
     * Runs button results on the main thread. Drops them once the server-transfer snapshot has been
     * taken, and for players who left. Button callbacks stay alive for 5 minutes even after the
     * dialog closes, so if one is pressed (by a modified client) during the transfer lock, a trade
     * such as a sale or auction listing would run outside the snapshot and duplicate items.
     */
    private void onMain(Player player, Runnable action) {
        tasks.main()
                .execute(
                        () -> {
                            if (player.isOnline() && !frozen.test(player.getUniqueId())) {
                                action.run();
                            }
                        });
    }

    @Override
    public void notice(Player player, Component title, List<Component> body) {
        Dialog dialog =
                Dialog.create(
                        factory ->
                                factory.empty().base(base(title, body)).type(DialogType.notice()));
        player.showDialog(dialog);
    }

    @Override
    public void confirm(
            Player player,
            Component title,
            List<Component> body,
            Component yes,
            Component no,
            Consumer<Boolean> result) {
        ActionButton yesButton =
                ActionButton.builder(yes)
                        .action(
                                DialogAction.customClick(
                                        (response, audience) ->
                                                onMain(player, () -> result.accept(true)),
                                        ONCE))
                        .build();
        ActionButton noButton =
                ActionButton.builder(no)
                        .action(
                                DialogAction.customClick(
                                        (response, audience) ->
                                                onMain(player, () -> result.accept(false)),
                                        ONCE))
                        .build();
        Dialog dialog =
                Dialog.create(
                        factory ->
                                factory.empty()
                                        .base(base(title, body))
                                        .type(DialogType.confirmation(yesButton, noButton)));
        player.showDialog(dialog);
    }

    @Override
    public void choose(
            Player player,
            Component title,
            List<Component> body,
            List<Choice> choices,
            int columns,
            Consumer<Integer> result) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            int index = i;
            Choice choice = choices.get(i);
            buttons.add(
                    ActionButton.builder(choice.label())
                            .tooltip(choice.tooltip())
                            .action(
                                    DialogAction.customClick(
                                            (response, audience) ->
                                                    onMain(player, () -> result.accept(index)),
                                            ONCE))
                            .build());
        }
        Dialog dialog =
                Dialog.create(
                        factory ->
                                factory.empty()
                                        .base(base(title, body))
                                        .type(
                                                DialogType.multiAction(buttons)
                                                        .columns(columns)
                                                        .build()));
        player.showDialog(dialog);
    }

    @Override
    public void input(
            Player player,
            Component title,
            List<Component> body,
            Component label,
            String initial,
            int maxLength,
            boolean multiline,
            Component confirm,
            Component cancel,
            Consumer<String> result) {
        TextDialogInput.Builder text =
                DialogInput.text(INPUT_KEY, label)
                        .initial(initial)
                        .maxLength(Math.max(1, maxLength))
                        .width(300);
        if (multiline) {
            text.multiline(TextDialogInput.MultilineOptions.create(8, 120));
        }
        ActionButton ok =
                ActionButton.builder(confirm)
                        .action(
                                DialogAction.customClick(
                                        (response, audience) -> {
                                            String value = response.getText(INPUT_KEY);
                                            String v = value == null ? "" : value.strip();
                                            onMain(player, () -> result.accept(v));
                                        },
                                        ONCE))
                        .build();
        ActionButton no = ActionButton.builder(cancel).build();
        Dialog dialog =
                Dialog.create(
                        factory ->
                                factory.empty()
                                        .base(
                                                DialogBase.builder(title)
                                                        .canCloseWithEscape(true)
                                                        .body(
                                                                body.stream()
                                                                        .map(
                                                                                DialogBody
                                                                                        ::plainMessage)
                                                                        .toList())
                                                        .inputs(List.of(text.build()))
                                                        .build())
                                        .type(DialogType.confirmation(ok, no)));
        player.showDialog(dialog);
    }

    private static final String INPUT_KEY = "value";

    private static DialogBase base(Component title, List<Component> body) {
        return DialogBase.builder(title)
                .canCloseWithEscape(true)
                .body(body.stream().map(DialogBody::plainMessage).toList())
                .build();
    }
}
