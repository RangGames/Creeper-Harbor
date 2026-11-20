package wiki.creeper.harbor.npc.dialog.present;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.NpcKeys;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.SpeechText;

/**
 * HOLOGRAM: NPC 머리 위 말풍선 (TextDisplay, 그 플레이어에게만 보임). 타자 효과로 글자를 차례로 드러내고, 선택지는 채팅 버튼 줄로 보낸다. 말풍선은
 * 저장하지 않고(persistent false) harbor:npc_speech 태그를 달아 비정상 종료 잔여물을 NpcManager가 지운다. NPC가 이 서버에 서 있지
 * 않으면 CHAT으로 대신한다.
 */
final class HologramPresenter implements DialogPresenter {

    /** 플레이어 한 명의 말풍선. */
    private static final class Bubble {
        final NpcId npc;
        final TextDisplay display;
        @Nullable BukkitTask typing;
        @Nullable BukkitTask expire;

        Bubble(NpcId npc, TextDisplay display) {
            this.npc = npc;
            this.display = display;
        }

        void cancelTasks() {
            if (typing != null) {
                typing.cancel();
                typing = null;
            }
            if (expire != null) {
                expire.cancel();
                expire = null;
            }
        }
    }

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final NpcManager npcs;
    private final ChatButtons buttons;
    private final ChatPresenter fallback;
    private final Map<UUID, Bubble> bubbles = new HashMap<>();

    HologramPresenter(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            NpcManager npcs,
            ChatButtons buttons,
            ChatPresenter fallback) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.npcs = npcs;
        this.buttons = buttons;
        this.fallback = fallback;
    }

    private NpcBalance balance() {
        return npcs.balance();
    }

    @Override
    public void show(Player player, Frame frame) {
        Component text = frame.text();
        Component hint =
                api.messages()
                        .get(
                                frame.nextOnly()
                                        ? "npc.dialog.style.hologram_next"
                                        : "npc.dialog.style.hologram_choices");
        if (!bubble(player, frame.npc(), frame.speaker(), text, hint)) {
            fallback.show(player, frame);
            return;
        }
        buttons.send(player, frame);
    }

    /** 세션 없는 한마디. 몇 초 뒤 사라진다. NPC가 없으면 false (부르는 쪽이 다른 방식으로 대신). */
    boolean bark(Player player, NpcId npc, Component speaker, Component line) {
        if (!bubble(player, npc, speaker, line, null)) {
            return false;
        }
        Bubble b = bubbles.get(player.getUniqueId());
        if (b != null) {
            long ticks = SpeechText.autoTicks(balance().barkSeconds());
            b.expire =
                    tasks.later(
                            ticks,
                            () -> {
                                if (bubbles.get(player.getUniqueId()) == b) {
                                    clear(player);
                                }
                            });
        }
        return true;
    }

    private boolean bubble(
            Player player,
            NpcId npc,
            Component speaker,
            @Nullable Component text,
            @Nullable Component hint) {
        Location home = npcs.home(npc);
        if (home == null || !home.getWorld().equals(player.getWorld())) {
            return false;
        }
        Bubble b = bubbles.get(player.getUniqueId());
        if (b == null || !b.npc.equals(npc) || !b.display.isValid()) {
            clear(player);
            b = new Bubble(npc, spawn(player, home.add(0, balance().hologramOffset(), 0)));
            bubbles.put(player.getUniqueId(), b);
        }
        b.cancelTasks();
        Component body = text == null ? Component.empty() : text;
        int length = SpeechText.length(body);
        int perTick = balance().typewriterCharsPerTick();
        int steps = SpeechText.typewriterTicks(length, perTick);
        if (steps <= 1) {
            b.display.text(compose(speaker, body, hint));
            return true;
        }
        Bubble bubble = b;
        int[] shown = {0};
        bubble.display.text(compose(speaker, Component.empty(), null));
        bubble.typing =
                tasks.timer(
                        1,
                        1,
                        () -> {
                            if (!bubble.display.isValid()) {
                                bubble.cancelTasks();
                                return;
                            }
                            shown[0] = Math.min(length, shown[0] + perTick);
                            boolean done = shown[0] >= length;
                            bubble.display.text(
                                    compose(
                                            speaker,
                                            done ? body : SpeechText.slice(body, shown[0]),
                                            done ? hint : null));
                            if (done && bubble.typing != null) {
                                bubble.typing.cancel();
                                bubble.typing = null;
                            }
                        });
        return true;
    }

    private Component compose(Component speaker, Component text, @Nullable Component hint) {
        return api.messages()
                .get(
                        "npc.dialog.style.hologram",
                        Placeholder.component("speaker", speaker),
                        Placeholder.component("text", text),
                        Placeholder.component("hint", hint == null ? Component.empty() : hint));
    }

    private TextDisplay spawn(Player viewer, Location at) {
        NpcBalance balance = balance();
        TextDisplay display =
                at.getWorld()
                        .spawn(
                                at,
                                TextDisplay.class,
                                t -> {
                                    t.setPersistent(false);
                                    t.setVisibleByDefault(false);
                                    t.setBillboard(Display.Billboard.CENTER);
                                    t.setLineWidth(balance.hologramLineWidth());
                                    t.setShadowed(false);
                                    t.setDefaultBackground(false);
                                    t.setBackgroundColor(
                                            Color.fromARGB(
                                                    balance.hologramBackgroundAlpha(), 0, 0, 0));
                                    t.setViewRange(balance.hologramViewRange());
                                    t.setAlignment(TextDisplay.TextAlignment.CENTER);
                                    NpcKeys.tag(t, NpcKeys.SPEECH, viewer.getUniqueId().toString());
                                });
        viewer.showEntity(plugin, display);
        return display;
    }

    @Override
    public void refresh(Player player) {
        Bubble b = bubbles.get(player.getUniqueId());
        if (b == null) {
            return;
        }
        if (!b.display.isValid()
                || npcs.home(b.npc) == null
                || !b.display.getWorld().equals(player.getWorld())) {
            clear(player); // NPC가 사라졌거나 플레이어가 다른 월드로 감
        }
    }

    @Override
    public void clear(Player player) {
        remove(player.getUniqueId());
    }

    void remove(UUID uuid) {
        Bubble b = bubbles.remove(uuid);
        if (b != null) {
            b.cancelTasks();
            b.display.remove();
        }
    }

    /** 플러그인 종료: 모든 말풍선 제거. */
    void clearAll() {
        for (UUID uuid : List.copyOf(bubbles.keySet())) {
            remove(uuid);
        }
    }

    /** 이 플레이어에게 말풍선이 떠 있는지 (watch 대상). */
    boolean has(UUID uuid) {
        return bubbles.containsKey(uuid);
    }
}
