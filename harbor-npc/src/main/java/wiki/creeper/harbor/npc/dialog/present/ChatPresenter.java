package wiki.creeper.harbor.npc.dialog.present;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;

/** CHAT: chat line + button row. */
final class ChatPresenter implements DialogPresenter {

    private final HarborApi api;
    private final ChatButtons buttons;

    ChatPresenter(HarborApi api, ChatButtons buttons) {
        this.api = api;
        this.buttons = buttons;
    }

    @Override
    public void show(Player player, Frame frame) {
        Component text = frame.text();
        if (text != null) {
            player.sendMessage(line(frame.speaker(), text));
        }
        buttons.send(player, frame);
    }

    Component line(Component speaker, Component text) {
        return api.messages()
                .get(
                        "npc.dialog.style.chat",
                        Placeholder.component("speaker", speaker),
                        Placeholder.component("text", text));
    }
}
