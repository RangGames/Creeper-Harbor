package wiki.creeper.harbor.npc.dialog.present;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;

/** ACTIONBAR: 액션바 대사 (사라지지 않게 refresh로 다시 보냄) + 채팅 버튼 줄. */
final class ActionBarPresenter implements DialogPresenter {

    private final HarborApi api;
    private final ChatButtons buttons;
    private final Map<UUID, Component> showing = new HashMap<>();
    private final Map<UUID, Integer> sentAt = new HashMap<>();
    private final java.util.function.IntSupplier refreshTicks;

    ActionBarPresenter(
            HarborApi api, ChatButtons buttons, java.util.function.IntSupplier refreshTicks) {
        this.api = api;
        this.buttons = buttons;
        this.refreshTicks = refreshTicks;
    }

    @Override
    public void show(Player player, Frame frame) {
        Component text = frame.text();
        if (text != null) {
            Component bar = line(frame.speaker(), text);
            showing.put(player.getUniqueId(), bar);
            send(player, bar);
        } else {
            showing.remove(player.getUniqueId());
        }
        buttons.send(player, frame);
    }

    Component line(Component speaker, Component text) {
        return api.messages()
                .get(
                        "npc.dialog.style.actionbar",
                        Placeholder.component("speaker", speaker),
                        Placeholder.component("text", text));
    }

    void send(Player player, Component bar) {
        api.ui().actionBar(player, bar);
        sentAt.put(player.getUniqueId(), Bukkit.getCurrentTick());
    }

    @Override
    public void refresh(Player player) {
        Component bar = showing.get(player.getUniqueId());
        Integer at = sentAt.get(player.getUniqueId());
        if (bar != null
                && (at == null || Bukkit.getCurrentTick() - at >= refreshTicks.getAsInt())) {
            send(player, bar);
        }
    }

    @Override
    public void clear(Player player) {
        if (showing.remove(player.getUniqueId()) != null) {
            api.ui().actionBar(player, Component.empty());
        }
        sentAt.remove(player.getUniqueId());
    }
}
