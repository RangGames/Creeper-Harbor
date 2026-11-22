package wiki.creeper.harbor.npc.dialog.present;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * DIALOG 밖의 방식이 쓰는 채팅 버튼 줄. 버튼마다 한 번만 쓰는 클릭 콜백을 달고, 누른 사람이 그 플레이어일 때만 메인 스레드에서 {@code onClick}을
 * 부른다. 오래된 줄을 누르면 받는 쪽이 nonce로 무시한다.
 */
final class ChatButtons {

    private final HarborApi api;
    private final Executor main;
    private final Supplier<Duration> lifetime;

    ChatButtons(HarborApi api, Executor main, Supplier<Duration> lifetime) {
        this.api = api;
        this.main = main;
        this.lifetime = lifetime;
    }

    /** 버튼 줄을 채팅으로 보낸다. */
    void send(Player player, Frame frame) {
        player.sendMessage(line(player, frame.choices(), frame.nextOnly(), frame.onClick()));
    }

    Component line(Player player, List<Dialogs.Choice> choices, boolean next, IntConsumer onClick) {
        ClickCallback.Options options =
                ClickCallback.Options.builder().uses(1).lifetime(lifetime.get()).build();
        List<Component> buttons = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            Dialogs.Choice choice = choices.get(i);
            int index = i;
            Component tooltip = choice.tooltip();
            if (tooltip.equals(Component.empty()) && next) {
                tooltip = api.messages().get("npc.dialog.style.next_hint");
            }
            Component button =
                    api.messages()
                            .get(
                                    "npc.dialog.style.button",
                                    Placeholder.component("label", choice.label()))
                            .clickEvent(
                                    ClickEvent.callback(
                                            audience -> {
                                                if (audience instanceof Player clicker
                                                        && clicker.getUniqueId()
                                                                .equals(player.getUniqueId())) {
                                                    // 서버 이동 저장본을 뜬 뒤 누른 버튼은 버린다 (대화 보상이 저장본 밖에서
                                                    // 실행됨)
                                                    onMain(
                                                            () -> {
                                                                if (!api.players()
                                                                        .frozen(
                                                                                clicker
                                                                                        .getUniqueId())) {
                                                                    onClick.accept(index);
                                                                }
                                                            });
                                                }
                                            },
                                            options));
            if (!tooltip.equals(Component.empty())) {
                button = button.hoverEvent(HoverEvent.showText(tooltip));
            }
            buttons.add(button);
        }
        Component joined =
                Component.join(
                        JoinConfiguration.separator(
                                api.messages().get("npc.dialog.style.separator")),
                        buttons);
        return api.messages()
                .get("npc.dialog.style.choices", Placeholder.component("buttons", joined));
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            main.execute(task);
        }
    }
}
