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

/** Paper Dialog API 래퍼. API가 바뀌면 이 클래스만 고친다. */
@SuppressWarnings("UnstableApiUsage")
public final class DialogManager implements Dialogs {

    private static final ClickCallback.Options ONCE =
            ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();

    private final Tasks tasks;

    public DialogManager(Tasks tasks) {
        this.tasks = tasks;
    }

    private volatile Predicate<UUID> frozen = uuid -> false;

    /** 서버 이동 저장본을 뜬 플레이어 판정 (PlayerManager.frozen). Core 시작 때 넣는다. */
    public void setFrozen(Predicate<UUID> frozen) {
        this.frozen = frozen;
    }

    /**
     * 버튼 결과를 메인 스레드에서 실행한다. 서버 이동 저장본을 뜬 뒤면 버리고, 나간 플레이어도 버린다. 버튼 콜백은 창을 닫은 뒤에도 5분 동안 살아 있어서 이동 잠금
     * 중에 (변조 클라이언트가) 누르면 판매·경매 등록 같은 거래가 저장본 밖에서 실행돼 복제된다.
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
